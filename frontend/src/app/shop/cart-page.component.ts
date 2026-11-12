import { CurrencyPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { UI } from '../ui/ui';
import { CartService, cartError } from './cart.service';
import { Cart, CartItem } from './shop.models';
import { ShippingEstimateComponent } from './shipping-estimate.component';
import { watchUnsavedChanges } from '../unsaved-changes';

@Component({
  selector: 'app-cart-page',
  imports: [...UI, RouterLink, CurrencyPipe, ShippingEstimateComponent],
  template: `
    <header class="shop-header"><a class="brand" routerLink="/">c. <span>commerce</span></a><a routerLink="/">Continuar explorando</a></header>
    <main class="shop-wrap">
      <nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/">Início</a><span>/</span><span aria-current="page">Sacola</span></nav>
      <span class="overline">SEUS FAVORITOS, JUNTOS</span><h1>Minha sacola.</h1>
      <p class="muted">Adicionar à sacola não reserva estoque. Confira os valores atuais antes de continuar.</p>
      <p class="muted">{{ cart.customer() ? 'Sua sacola está vinculada à conta desta loja e será recuperada ao entrar novamente.' : 'A sacola anônima é mantida nesta aba ao atualizar. Entre na sua conta para recuperá-la também em outra visita ou dispositivo.' }}</p>
      @if (error()) { <p class="field-error">{{ error() }}</p><button type="button" [disabled]="cart.busy()" (click)="load()">Atualizar carrinho</button> }
      @if (cart.busy()) { <p role="status">Atualizando sacola...</p> }
      @if (message()) { <div class="message success" role="status">{{ message() }}</div> }
      @if (cart.cart()?.items?.length) {
        <div class="cart-layout"><section aria-label="Itens da sacola">
          @for (item of cart.cart()?.items; track item.variantId) {
            <app-record-card [staticCard]="true" [label]="item.name">
              <div class="cart-item"><h2><a [routerLink]="['/produtos', item.productId]">{{ item.name }}</a></h2>
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
        </section><aside class="cart-summary"><h2>Resumo da sacola</h2><p>{{ cart.count() }} unidade(s)</p><p class="total">{{ total() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p><p class="muted">Subtotal dos produtos, sem frete.</p>
          <app-shipping-estimate [quantity]="cart.count()" [disabled]="cart.busy() || !available()" />
          <hr /><a class="button" routerLink="/checkout">Continuar para o checkout</a>
          <p>Entre com sua conta de cliente para reservar os itens e criar um pedido pendente. Pagamento online ainda não está disponível.</p>
        </aside></div>
      } @else if (!cart.busy() && !error()) {
        <section class="empty"><h2>Sua sacola está esperando seus favoritos.</h2><p>Explore a coleção e escolha seu próximo detalhe favorito.</p><a class="button" routerLink="/">Explorar produtos</a></section>
      }
    </main><footer class="shop-footer">commerce. · Uma loja feita para você.</footer>`,
  styleUrl: './shop.css',
})
export class CartPageComponent {
  protected readonly cart = inject(CartService);
  private readonly destroy = inject(DestroyRef);
  protected readonly error = signal('');
  protected readonly total = computed(() => this.cart.cart()?.items.reduce((sum, item) => sum + item.price * item.quantity, 0) ?? 0);
  protected readonly available = computed(() => this.cart.cart()?.items.every((item) => item.available) ?? false);
  constructor() { watchUnsavedChanges(this); this.load(); }
  hasUnsavedChanges(): boolean { return false; }
  isSaving(): boolean { return this.cart.busy(); }
  protected readonly message = signal('');
  protected load(): void { this.run(this.cart.load()); }
  protected change(item: CartItem, quantity: number): void { this.run(this.cart.setQuantity(item.productId, item.variantId, quantity), 'Quantidade atualizada.'); }
  protected remove(item: CartItem): void { this.run(this.cart.remove(item.productId, item.variantId), 'Item removido da sacola.'); }
  private run(request: Observable<Cart | null>, success = ''): void {
    if (this.cart.busy()) return;
    this.error.set(''); this.message.set('');
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => this.message.set(success),
      error: (error: unknown) => this.error.set(cartError(error)),
    });
  }
}
