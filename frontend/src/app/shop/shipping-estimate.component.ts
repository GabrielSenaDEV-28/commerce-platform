import { CurrencyPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, Input, OnChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import { UI } from '../ui/ui';
import { CartService } from './cart.service';
import { QuoteResult } from './shop.models';

@Component({
  selector: 'app-shipping-estimate',
  imports: [...UI, ReactiveFormsModule, CurrencyPipe],
  template: `
    <section class="shipping" aria-labelledby="shipping-title">
      <h3 id="shipping-title">Entrega no seu endereço</h3><p>Consulte as opções disponíveis para seu CEP.</p>
      <form (submit)="$event.preventDefault(); quote()" novalidate>
        <label for="destination-cep">CEP de destino</label>
        <div class="cep-row"><input id="destination-cep" [formControl]="postalCode" inputmode="numeric" autocomplete="postal-code" placeholder="00000-000" maxlength="9" [attr.aria-invalid]="postalCode.touched && postalCode.invalid" [attr.aria-describedby]="postalCode.touched && postalCode.invalid ? 'destination-cep-error' : null" />
          <button type="submit" [disabled]="loading() || disabled">Calcular frete</button></div>
        @if (postalCode.touched && postalCode.invalid) { <p id="destination-cep-error" class="field-error">Informe um CEP com 8 números, com ou sem hífen.</p> }
      </form>
      @if (loading()) { <p role="status">Consultando transportadoras...</p> }
      @if (error()) { <p class="field-error">{{ error() }}</p> }
      @if (result(); as result) {
        @for (quote of result.quotes; track quote.id) {
          <app-record-card [staticCard]="true" [label]="quote.name"><strong>{{ quote.name }}</strong>
            <p>{{ quote.deliveryDays }} dia(s) úteis estimados · {{ quote.price | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p></app-record-card>
        }
        @if (!result.quotes.length) { <p>Nenhuma opção de entrega disponível para este CEP.</p> }
        @if (result.unavailable.length) { <p>Algumas transportadoras não oferecem serviço para esta consulta.</p> }
        <small>Estimativa sem reserva de estoque. Preço e prazo serão confirmados na finalização do pedido.</small>
      }
    </section>`,
  styles: [`
    .shipping { padding-top:24px; border-top:1px solid #dfe7df; margin-top:24px; }
    h3 { font:400 1.25rem Georgia,serif; color:#294b40; } p:not(.field-error), small { color:#718275; font-size:.82rem; line-height:1.6; }
    label { display:block; font-size:.8rem; margin-bottom:8px; } .cep-row { display:flex; gap:10px; flex-wrap:wrap; } input { min-width:0; flex:1; }
  `],
})
export class ShippingEstimateComponent implements OnChanges {
  @Input() productId: string | null = null;
  @Input() variantId: string | null = null;
  @Input() quantity = 1;
  @Input() disabled = false;
  private readonly http = inject(HttpClient);
  private readonly cart = inject(CartService);
  private readonly destroy = inject(DestroyRef);
  private request?: Subscription;
  protected readonly postalCode = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^\d{5}-?\d{3}$/)] });
  protected readonly loading = signal(false);
  protected readonly result = signal<QuoteResult | null>(null);
  protected readonly error = signal('');
  constructor() {
    this.postalCode.valueChanges.pipe(takeUntilDestroyed(this.destroy)).subscribe(() => this.clear());
  }
  ngOnChanges(): void { this.clear(); }
  private clear(): void { this.request?.unsubscribe(); this.loading.set(false); this.result.set(null); this.error.set(''); }
  protected quote(): void {
    if (this.loading() || this.disabled) return;
    if (this.postalCode.invalid) { this.postalCode.markAsTouched(); return; }
    if (this.productId && !this.variantId) { this.error.set('Selecione uma variante para consultar o frete.'); return; }
    this.clear(); this.loading.set(true);
    const url = this.productId && this.variantId ? `/api/public/catalog/products/${encodeURIComponent(this.productId)}/variants/${encodeURIComponent(this.variantId)}/shipping-estimates`
      : '/api/public/cart/shipping-estimates';
    const body = this.productId ? { postalCode: this.postalCode.value.replace('-', ''), quantity: this.quantity }
      : { postalCode: this.postalCode.value.replace('-', '') };
    this.request = this.http.post<QuoteResult>(url, body, this.productId ? {} : { headers: this.cart.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (result) => { this.result.set(result); this.loading.set(false); },
        error: (error: unknown) => {
          this.loading.set(false);
          this.error.set(error instanceof HttpErrorResponse && error.status === 409
            ? 'Este item não pode ser cotado nesta quantidade ou está sem medidas de envio. Confira a seleção ou consulte a loja.'
            : 'Frete indisponível no momento. A integração da loja pode não estar configurada. Tente novamente mais tarde.');
        },
      });
  }
}
