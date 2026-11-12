import { CurrencyPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, Input, OnChanges, computed, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, Subscription, expand, reduce } from 'rxjs';
import { UI } from '../ui/ui';
import { CartService, cartError } from './cart.service';
import { Page, PublicProduct, PublicVariant } from './shop.models';
import { ShippingEstimateComponent } from './shipping-estimate.component';
import { DEFAULT_COLORS } from '../ui/variant-colors';

const optionKey = (value: string | null): string | null => value?.trim().toLowerCase() ?? null;

@Component({
  selector: 'app-purchase-options',
  imports: [...UI, ReactiveFormsModule, CurrencyPipe, RouterLink, ShippingEstimateComponent],
  template: `
    @if (loading()) { <p role="status">Carregando opções do produto...</p> }
    @if (loadError()) { <p class="field-error">{{ loadError() }}</p><button type="button" (click)="load()">Tentar novamente</button> }
    @if (variants(); as options) {
      <form [attr.inert]="cart.busy() ? '' : null" (submit)="$event.preventDefault(); add()" novalidate>
        @if (options.length) {
          @if (colors().length > 1 || colors()[0] !== null) {
            <fieldset><legend>Cor</legend><div class="option-row color-options" aria-label="Cores do produto">
              @for (color of colors(); track color) {
                <button type="button" class="choice color-option" [class.selected]="colorChoice() === color" [class.unavailable]="!colorAvailable(color)"
                  [disabled]="!colorAvailable(color)" [attr.aria-label]="optionLabel('color', color) + (!colorAvailable(color) ? ' — indisponível nesta combinação' : '')"
                  [attr.aria-pressed]="colorChoice() === color" (click)="chooseColor(color)">
                  <span class="swatch" [class.no-swatch]="!colorHex(color)" [style.background-color]="colorHex(color)"></span>
                  @if (!colorAvailable(color)) { <span class="unavailable-mark" aria-hidden="true">×</span> }
                </button>
              }
            </div></fieldset>
            @if (colors().some(missingSwatch)) { <p>Amostras listradas indicam cores sem tonalidade cadastrada.</p> }
          }
          @if (sizes().length > 1 || sizes()[0] !== null) {
            <fieldset><legend>Tamanho</legend><div class="option-row size-options" aria-label="Tamanhos do produto">
              @for (size of sizes(); track size) {
                <button type="button" class="choice size-option" [class.selected]="sizeChoice() === size" [class.unavailable]="!sizeAvailable(size)"
                  [disabled]="!sizeAvailable(size)" [attr.aria-label]="optionLabel('size', size) + (!sizeAvailable(size) ? ' — indisponível nesta combinação' : '')"
                  [attr.aria-pressed]="sizeChoice() === size" (click)="chooseSize(size)">
                  {{ optionLabel('size', size) }} @if (!sizeAvailable(size)) { <span class="unavailable-mark" aria-hidden="true">×</span> }
                </button>
              }
            </div></fieldset>
          }
          <p class="selection-help">Escolha cor e tamanho. Para trocar uma combinação bloqueada, clique novamente na opção selecionada para desmarcá-la. Opções com X não estão disponíveis.</p>
        } @else { <p>Este produto não possui variantes disponíveis.</p> }
        @if (selectionError()) { <p class="field-error">Selecione uma combinação disponível.</p> }
        <div class="price" aria-live="polite">{{ (selected()?.price ?? product.price) | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</div>
        @if (!selected()) { <p>Preço padrão. Selecione uma variante para confirmar o valor.</p> }
        @if (selected(); as variant) { <p>{{ variant.available ? 'Disponível para adicionar ao carrinho' : 'Esta combinação está esgotada' }}</p> }
        <label for="purchase-quantity">Quantidade</label><input id="purchase-quantity" type="number" min="1" step="1" [max]="selected()?.quantity ?? 1" [formControl]="quantity" [attr.aria-invalid]="quantity.touched && quantity.invalid" [attr.aria-describedby]="quantity.touched && quantity.invalid ? 'purchase-quantity-error' : null" />
        @if (quantity.touched && quantity.invalid) { <p id="purchase-quantity-error" class="field-error">Informe uma quantidade inteira dentro do estoque disponível.</p> }
        <div class="actions"><button type="submit" class="primary" [disabled]="loading() || cart.busy() || !hasStock()">{{ cart.busy() ? 'Aguarde...' : 'Adicionar ao carrinho' }}</button>
          <button type="button" [disabled]="loading() || cart.busy() || !hasStock()" (click)="add(true)">Comprar agora</button></div>
      </form>
      @if (error()) { <p class="field-error">{{ error() }} <a routerLink="/carrinho">Conferir carrinho</a></p> }
      @if (added()) { <div class="message success" role="status">Produto adicionado à sacola. <a routerLink="/carrinho">Ver carrinho</a></div> }
      <p class="purchase-note">Comprar agora adiciona a seleção à sacola e abre sua revisão, sem remover outros itens. No checkout você poderá criar um pedido pendente. Pagamento online ainda não está disponível.</p>
      @if (showShipping) { <app-shipping-estimate [productId]="product.id" [variantId]="selected()?.id ?? null" [quantity]="quantity.value" [disabled]="!selected()?.available || quantity.invalid || cart.busy()" /> }
    }`,
  styles: [`
    :host { display:block; } fieldset { border:0; padding:0; margin:20px 0; min-width:0; }
    legend, label { font-size:.82rem; font-weight:650; color:#294b40; } label { display:block; margin-bottom:8px; }
    .option-row { display:flex; flex-wrap:nowrap; gap:12px; overflow-x:auto; padding:8px 4px; }
    .choice { position:relative; flex:0 0 auto; display:grid; place-items:center; width:32px; height:32px; min-height:32px; border:1px solid #bdcdbf; padding:3px; background:white; color:#294b40; font-size:.75rem; }
    .color-option, .swatch { border-radius:50%; }.swatch { display:block; width:24px; height:24px; border:1px solid #294b4033; }
    .no-swatch { background:repeating-linear-gradient(45deg,#e1e5df 0 5px,#fafbf8 5px 10px); }
    .size-option { width:auto; min-width:32px; padding:4px 8px; }
    .choice.selected { outline:1px solid #729b7b; outline-offset:2px; border-color:#729b7b; }
    .choice.unavailable { opacity:1; cursor:not-allowed; }
    .unavailable-mark { position:absolute; inset:0; display:grid; place-items:center; border-radius:inherit; background:#bbc0b9ba; color:transparent; font-size:0; }
    .unavailable-mark::before, .unavailable-mark::after { content:''; position:absolute; top:50%; left:50%; width:12px; height:1px; background:#52675c; transform:translate(-50%,-50%) rotate(45deg); }
    .unavailable-mark::after { transform:translate(-50%,-50%) rotate(-45deg); }
    @media(pointer:coarse) { .choice::after { content:''; position:absolute; inset:-6px; } }
    .selection-help { font-size:.73rem; }.price { font:400 2.4rem Georgia,serif; color:#294b40; margin:22px 0 8px; }
    p, small { color:#718275; font-size:.8rem; line-height:1.6; } p.field-error { color:#8c342d; } .purchase-note { font-size:.74rem; margin-top:18px; }
    input { width:100px; } .actions { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin-top:16px; }
    .success { background:#ecf5eb; color:#315d42; margin-top:18px; } @media(max-width:480px) { .actions button { flex:1; } }
  `],
})
export class PurchaseOptionsComponent implements OnChanges {
  @Input({ required: true }) product!: PublicProduct;
  @Input() showShipping = true;
  readonly additionConfirmed = output<void>();
  protected readonly cart = inject(CartService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private request?: Subscription;
  protected readonly variants = signal<PublicVariant[] | null>(null);
  protected readonly colorChoice = signal<string | null | undefined>(undefined);
  protected readonly sizeChoice = signal<string | null | undefined>(undefined);
  protected readonly colors = computed(() => [...new Set(this.variants()?.map((variant) => optionKey(variant.color)) ?? [])]);
  protected readonly sizes = computed(() => [...new Set(this.variants()?.map((variant) => optionKey(variant.size)) ?? [])]);
  protected readonly hasStock = computed(() => this.variants()?.some((variant) => this.inStock(variant)) ?? false);
  protected readonly selected = computed(() => this.variants()?.find((variant) => this.inStock(variant)
    && optionKey(variant.color) === this.colorChoice() && optionKey(variant.size) === this.sizeChoice()) ?? null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal('');
  protected readonly error = signal('');
  protected readonly added = signal(false);
  protected readonly selectionError = signal(false);
  protected readonly quantity = new FormControl(1, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)] });
  ngOnChanges(): void { this.load(); }
  protected load(): void {
    this.request?.unsubscribe(); this.variants.set(null); this.colorChoice.set(undefined); this.sizeChoice.set(undefined); this.added.set(false);
    this.loading.set(true); this.loadError.set(''); this.error.set(''); this.selectionError.set(false);
    const fetch = (page: number) => this.http.get<Page<PublicVariant>>(`/api/public/catalog/products/${encodeURIComponent(this.product.id)}/variants`, { params: { page, size: 100 } });
    this.request = fetch(0).pipe(
      expand((page) => page.page + 1 < page.totalPages ? fetch(page.page + 1) : EMPTY),
      reduce((items, page) => [...items, ...page.content], [] as PublicVariant[]),
      takeUntilDestroyed(this.destroy),
    ).subscribe({
        next: (variants) => {
          this.variants.set(variants); this.loading.set(false);
          if (this.colors().length === 1 && this.colors()[0] === null) this.colorChoice.set(null);
          if (this.sizes().length === 1 && this.sizes()[0] === null) this.sizeChoice.set(null);
          if (variants.length === 1 && this.inStock(variants[0])) {
            this.colorChoice.set(optionKey(variants[0].color)); this.sizeChoice.set(optionKey(variants[0].size));
          }
          this.updateQuantity();
        },
        error: () => { this.loading.set(false); this.loadError.set('Não foi possível carregar as variantes. Tente novamente.'); },
      });
  }
  private inStock(variant: PublicVariant): boolean { return variant.available && variant.quantity > 0; }
  protected colorAvailable(color: string | null): boolean {
    return this.variants()?.some((variant) => this.inStock(variant) && optionKey(variant.color) === color
      && (this.sizeChoice() === undefined || optionKey(variant.size) === this.sizeChoice())) ?? false;
  }
  protected sizeAvailable(size: string | null): boolean {
    return this.variants()?.some((variant) => this.inStock(variant) && optionKey(variant.size) === size
      && (this.colorChoice() === undefined || optionKey(variant.color) === this.colorChoice())) ?? false;
  }
  protected colorHex(color: string | null): string | null {
    return this.variants()?.find((variant) => optionKey(variant.color) === color && variant.colorHex)?.colorHex
      ?? DEFAULT_COLORS.find((option) => optionKey(option.name) === color)?.colorHex ?? null;
  }
  protected readonly missingSwatch = (color: string | null): boolean => !this.colorHex(color);
  protected optionLabel(field: 'color' | 'size', value: string | null): string {
    return this.variants()?.find((variant) => optionKey(variant[field]) === value)?.[field] ?? (field === 'color' ? 'Sem cor' : 'Único');
  }
  protected chooseColor(color: string | null): void {
    if (this.cart.busy() || !this.colorAvailable(color)) return;
    this.colorChoice.set(this.colorChoice() === color ? undefined : color); this.updateQuantity();
  }
  protected chooseSize(size: string | null): void {
    if (this.cart.busy() || !this.sizeAvailable(size)) return;
    this.sizeChoice.set(this.sizeChoice() === size ? undefined : size); this.updateQuantity();
  }
  private updateQuantity(): void {
    this.selectionError.set(false); this.added.set(false); this.error.set('');
    this.quantity.setValidators([Validators.required, Validators.min(1), Validators.max(this.selected()?.quantity ?? 1), Validators.pattern(/^\d+$/)]);
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
        queueMicrotask(() => {
          if (this.destroy.destroyed) return;
          this.additionConfirmed.emit();
          if (direct) void this.router.navigateByUrl('/carrinho');
        });
      },
      error: (error: unknown) => this.error.set(cartError(error)),
    });
  }
}
