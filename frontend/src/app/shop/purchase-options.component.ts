import { CurrencyPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, Input, OnChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { UI } from '../ui/ui';
import { CartService, cartError } from './cart.service';
import { Page, PublicProduct, PublicVariant } from './shop.models';
import { ShippingEstimateComponent } from './shipping-estimate.component';

@Component({
  selector: 'app-purchase-options',
  imports: [...UI, ReactiveFormsModule, CurrencyPipe, RouterLink, ShippingEstimateComponent],
  template: `
    @if (loading()) { <p role="status">Carregando opções do produto...</p> }
    @if (loadError()) { <p class="field-error">{{ loadError() }}</p><button type="button" (click)="load(page())">Tentar novamente</button> }
    @if (variants(); as options) {
      <form [attr.inert]="cart.busy() ? '' : null" (submit)="$event.preventDefault(); add()" novalidate>
        <fieldset><legend>Escolha sua combinação de cor e tamanho</legend>
          <div class="variant-choices">@for (variant of options.content; track variant.id) {
            <button type="button" class="variant-choice" [class.selected]="selected()?.id === variant.id" [attr.aria-pressed]="selected()?.id === variant.id" (click)="choose(variant)">
              {{ variant.color || 'Cor única' }} · {{ variant.size || 'Tamanho único' }}
              <small>{{ variant.available ? (variant.price | currency:'BRL':'symbol':'1.2-2':'pt-BR') : 'Esgotado' }}</small>
            </button>
          }</div>
          @if (!options.content.length) { <p>Nenhuma variante disponível nesta página.</p> }
          @if (options.totalPages > 1) { <div class="actions"><button type="button" [disabled]="page() === 0" (click)="load(page() - 1)">Anterior</button><small>Página {{ page() + 1 }} de {{ options.totalPages }}</small><button type="button" [disabled]="page() + 1 >= options.totalPages" (click)="load(page() + 1)">Próxima</button></div> }
          @if (selectionError()) { <p class="field-error">Selecione uma combinação disponível.</p> }
        </fieldset>
        <div class="price" aria-live="polite">{{ (selected()?.price ?? product.price) | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</div>
        @if (!selected()) { <p>Preço padrão. Selecione uma variante para confirmar o valor.</p> }
        @if (selected(); as variant) { <p>{{ variant.available ? 'Disponível para adicionar ao carrinho' : 'Esta combinação está esgotada' }}</p> }
        <label for="purchase-quantity">Quantidade</label><input id="purchase-quantity" type="number" min="1" step="1" [max]="selected()?.quantity ?? 1" [formControl]="quantity" [attr.aria-invalid]="quantity.touched && quantity.invalid" [attr.aria-describedby]="quantity.touched && quantity.invalid ? 'purchase-quantity-error' : null" />
        @if (quantity.touched && quantity.invalid) { <p id="purchase-quantity-error" class="field-error">Informe uma quantidade inteira dentro do estoque disponível.</p> }
        <div class="actions"><button type="submit" class="primary" [disabled]="loading() || cart.busy() || (selected() !== null && !selected()?.available)">{{ cart.busy() ? 'Aguarde...' : 'Adicionar ao carrinho' }}</button>
          <button type="button" [disabled]="loading() || cart.busy() || (selected() !== null && !selected()?.available)" (click)="add(true)">Comprar agora</button></div>
      </form>
      @if (error()) { <p class="field-error">{{ error() }} <a routerLink="/carrinho">Conferir carrinho</a></p> }
      @if (added()) { <div class="message success" role="status">Produto adicionado à sacola. <a routerLink="/carrinho">Ver carrinho</a></div> }
      <p class="purchase-note">Comprar agora adiciona a seleção à sacola e abre sua revisão, sem remover outros itens. A finalização da compra e o pagamento ainda não estão disponíveis.</p>
      @if (showShipping) { <app-shipping-estimate [productId]="product.id" [variantId]="selected()?.id ?? null" [quantity]="quantity.value" [disabled]="!selected()?.available || quantity.invalid || cart.busy()" /> }
    }`,
  styles: [`
    :host { display:block; } fieldset { border:0; padding:0; margin:20px 0; min-width:0; }
    legend, label { font-size:.82rem; font-weight:650; color:#294b40; } label { display:block; margin-bottom:8px; }
    .variant-choices { display:flex; flex-wrap:wrap; gap:9px; margin:14px 0; } .variant-choice { text-align:left; }
    .variant-choice.selected { background:#e8f0e5; border-color:#52765c; box-shadow:inset 0 0 0 1px #52765c; }
    .variant-choice small { display:block; margin-top:5px; } .price { font:400 2.4rem Georgia,serif; color:#294b40; margin:22px 0 8px; }
    p, small { color:#718275; font-size:.8rem; line-height:1.6; } p.field-error { color:#8c342d; } .purchase-note { font-size:.74rem; margin-top:18px; }
    input { width:100px; } .actions { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin-top:16px; }
    .success { background:#ecf5eb; color:#315d42; margin-top:18px; } @media(max-width:480px) { .actions button { flex:1; } }
  `],
})
export class PurchaseOptionsComponent implements OnChanges {
  @Input({ required: true }) product!: PublicProduct;
  @Input() showShipping = true;
  protected readonly cart = inject(CartService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private request?: Subscription;
  protected readonly variants = signal<Page<PublicVariant> | null>(null);
  protected readonly selected = signal<PublicVariant | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal('');
  protected readonly error = signal('');
  protected readonly added = signal(false);
  protected readonly selectionError = signal(false);
  protected readonly page = signal(0);
  protected readonly quantity = new FormControl(1, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)] });
  ngOnChanges(): void { this.load(0); }
  protected load(page: number): void {
    if (this.cart.busy()) return;
    this.request?.unsubscribe(); this.selected.set(null); this.variants.set(null); this.added.set(false);
    this.loading.set(true); this.loadError.set(''); this.error.set(''); this.page.set(page); this.selectionError.set(false);
    this.request = this.http.get<Page<PublicVariant>>(`/api/public/catalog/products/${encodeURIComponent(this.product.id)}/variants`, { params: { page, size: 20 } })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (variants) => {
          this.variants.set(variants); this.loading.set(false);
          if (variants.totalElements === 1 && variants.content[0]) this.choose(variants.content[0]);
        },
        error: () => { this.loading.set(false); this.loadError.set('Não foi possível carregar as variantes. Tente novamente.'); },
      });
  }
  protected choose(variant: PublicVariant): void {
    this.selected.set(variant); this.selectionError.set(false); this.added.set(false); this.error.set('');
    this.quantity.setValidators([Validators.required, Validators.min(1), Validators.max(variant.quantity), Validators.pattern(/^\d+$/)]);
    this.quantity.reset(1);
  }
  protected add(direct = false): void {
    if (this.cart.busy() || this.loading()) return;
    const variant = this.selected();
    if (!variant?.available) { this.selectionError.set(true); return; }
    if (this.quantity.invalid) { this.quantity.markAsTouched(); return; }
    this.error.set(''); this.added.set(false);
    this.cart.add(this.product.id, variant.id, this.quantity.value).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => {
        this.added.set(true);
        if (direct) queueMicrotask(() => { if (!this.destroy.destroyed) void this.router.navigateByUrl('/carrinho'); });
      },
      error: (error: unknown) => this.error.set(cartError(error)),
    });
  }
}
