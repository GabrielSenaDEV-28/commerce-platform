import { CurrencyPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { UI } from '../ui/ui';
import { CartService, cartError } from './cart.service';
import { Cart, CartItem } from './shop.models';
import { watchUnsavedChanges } from '../unsaved-changes';
import { ToastService } from '../ui/toast.service';

@Component({
  selector: 'app-cart-page',
  imports: [...UI, RouterLink, CurrencyPipe],
  template: `
    <main class="shop-wrap">
      <nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/">Início</a><span>/</span><span aria-current="page">Sacola</span></nav>
      <span class="overline">SEUS FAVORITOS, JUNTOS</span><h1>Minha sacola.</h1>
      <p class="muted">Adicionar à sacola não reserva estoque. Confira os valores atuais antes de continuar.</p>
      <p class="muted">{{ cart.customer() ? 'Sua sacola está vinculada à conta desta loja e será recuperada ao entrar novamente.' : 'A sacola anônima é mantida nesta aba ao atualizar. Entre na sua conta para recuperá-la também em outra visita ou dispositivo.' }}</p>
      @if (error()) { <p class="field-error">{{ error() }}</p><button type="button" [disabled]="cart.busy()" (click)="load()">Atualizar carrinho</button> }
      @if (cart.busy()) { <p role="status">Atualizando sacola...</p> }
      @if (cart.cart()?.items?.length) {
        <div class="cart-layout"><section aria-label="Itens da sacola">
          @for (item of cart.cart()?.items; track item.variantId) {
            <app-record-card [staticCard]="true" [label]="item.name">
              <div class="cart-item"><h2><a [routerLink]="['/produtos', item.productId]">{{ item.name }}</a></h2>
                <label class="item-selection"><input type="checkbox" [checked]="item.available && !excluded().has(item.variantId)"
                  [disabled]="!item.available || cart.busy()" (change)="select(item.variantId, $event)" />
                  <span>Comprar {{ item.name }}{{ item.color ? ' · ' + item.color : '' }}{{ item.size ? ' · ' + item.size : '' }}</span></label>
                <p>{{ item.color || 'Cor única' }} · {{ item.size || 'Tamanho único' }}</p>
                <strong>{{ item.price | currency:'BRL':'symbol':'1.2-2':'pt-BR' }} / unidade</strong>
                <p>Subtotal: {{ item.price * item.quantity | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p>
                @if (!item.available) { <p class="field-error">Item indisponível nesta quantidade. Ajuste a quantidade ou remova-o.</p> }
                <div class="cart-actions">
                  <button type="button" [disabled]="cart.busy() || item.quantity <= 1" [attr.aria-label]="'Diminuir quantidade de ' + item.name" (click)="change(item, item.quantity - 1)">−</button>
                  <span>{{ item.quantity }} unidade(s)</span>
                  <button type="button" [disabled]="cart.busy() || item.quantity >= item.stockQuantity" [attr.aria-label]="'Aumentar quantidade de ' + item.name" (click)="change(item, item.quantity + 1)">+</button>
                  <button type="button" [disabled]="cart.busy()" (click)="remove(item)">Remover</button>
                </div>
              </div>
            </app-record-card>
          }
        </section><aside class="cart-summary"><h2>Resumo da sacola</h2><div aria-live="polite"><p>{{ quantity() }} unidade(s) selecionada(s)</p><p class="total">{{ total() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p></div><p class="muted">Subtotal dos itens selecionados, sem frete. Os demais ficam na sacola.</p>
          <p>O frete dos itens selecionados será calculado no checkout.</p>
          <hr />
          @if (quantity() && !cart.busy()) {
            <a class="button" routerLink="/checkout" [queryParams]="{ item: selectedIds() }">Continuar para o checkout</a>
          } @else {
            <button type="button" class="primary" disabled>Continuar para o checkout</button>
          }
          <p>Entre com sua conta de cliente para reservar os itens e criar um pedido pendente.</p>
        </aside></div>
      } @else if (!cart.busy() && !error()) {
        <section class="empty"><h2>Sua sacola está esperando seus favoritos.</h2><p>Explore a coleção e escolha seu próximo detalhe favorito.</p><a class="button" routerLink="/">Explorar produtos</a></section>
      }
    </main><footer class="shop-footer">commerce. · Uma loja feita para você.</footer>`,
  styleUrl: './shop.css',
  styles: ['.item-selection { display:flex; align-items:center; gap:.55rem; min-height:2.5rem; } .item-selection input { accent-color:var(--color-brand); width:1.15rem; height:1.15rem; }'],
})
export class CartPageComponent {
  protected readonly cart = inject(CartService);
  private readonly destroy = inject(DestroyRef);
  private readonly notifications = inject(ToastService);
  protected readonly error = signal('');
  protected readonly excluded = signal(new Set<string>());
  private readonly selected = computed(() => this.cart.cart()?.items.filter((item) => item.available && !this.excluded().has(item.variantId)) ?? []);
  protected readonly selectedIds = computed(() => this.selected().map((item) => item.variantId));
  protected readonly total = computed(() => this.selected().reduce((sum, item) => sum + item.price * item.quantity, 0));
  protected readonly quantity = computed(() => this.selected().reduce((sum, item) => sum + item.quantity, 0));
  constructor() { watchUnsavedChanges(this); this.load(); }
  hasUnsavedChanges(): boolean { return false; }
  isSaving(): boolean { return this.cart.busy(); }
  protected select(id: string, event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const excluded = new Set(this.excluded());
    if (event.target.checked) excluded.delete(id); else excluded.add(id);
    this.excluded.set(excluded);
  }
  protected load(): void {
    if (this.cart.busy()) return;
    this.error.set('');
    this.cart.load().pipe(takeUntilDestroyed(this.destroy)).subscribe({ error: () => { /* Reported by the global recovery toast. */ } });
  }
  protected change(item: CartItem, quantity: number): void { this.run(this.cart.setQuantity(item.productId, item.variantId, quantity), 'Quantidade atualizada.'); }
  protected remove(item: CartItem): void { this.run(this.cart.remove(item.productId, item.variantId), 'Item removido da sacola.'); }
  private run(request: Observable<Cart | null>, success = ''): void {
    if (this.cart.busy()) return;
    this.error.set('');
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => this.notifications.show('cart-update', success, 'success'),
      error: (error: unknown) => this.notifications.show('cart-update', cartError(error)),
    });
  }
}
