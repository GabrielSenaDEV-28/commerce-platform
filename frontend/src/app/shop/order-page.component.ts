import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { interval, timer } from 'rxjs';
import { UI } from '../ui/ui';
import { Order, PixPayment } from './checkout.models';
import { CheckoutService, checkoutError } from './checkout.service';
import { CheckoutExpiredDialogComponent } from '../ui/checkout-expired-dialog.component';

@Component({
  selector: 'app-order-page',
  imports: [...UI, RouterLink, CurrencyPipe, DatePipe, CheckoutExpiredDialogComponent],
  template: `
    <app-checkout-expired-dialog [open]="expiredNotice()" (confirmed)="acknowledgeExpiry()" />
    <main class="shop-wrap">
      <nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/">Início</a><span>/</span><a routerLink="/conta/pedidos">Pedidos</a><span>/</span><span aria-current="page">Detalhes</span></nav>
      <h1>Seu pedido.</h1>
      @if (loading()) { <p role="status">Consultando pedido...</p> }
      @if (error()) { <p class="field-error">{{ error() }}</p><button type="button" [disabled]="loading()" (click)="load()">Consultar novamente</button> }
      @if (order(); as info) {
        <section class="checkout-panel"><h2>Pedido {{ info.id }}</h2><p>Estado atual: <strong>{{ status(info.status) }}</strong></p><p>Criado em {{ info.createdAt | date:'dd/MM/yyyy HH:mm' }} · Prazo da reserva: {{ info.expiresAt | date:'dd/MM/yyyy HH:mm' }}</p>
          @if (info.status === 'PENDING') { <p>Criar este pedido não confirma pagamento, venda ou liberação para entrega/retirada.</p> }
          @if (info.status === 'PAYMENT_REVIEW') { <p>O pagamento precisa de revisão. O pedido não está liberado para entrega ou retirada.</p> }
        </section>
        @if (pixLoading() || pixError() || pix()?.enabled) {
          <section class="checkout-panel pix-panel" aria-label="Pagamento Pix">
            <div class="pix-heading"><div><span class="overline">PAGAMENTO</span><h2>Pix</h2></div>
              <span class="pix-badge" [class.confirmed]="paymentConfirmed()">{{ paymentConfirmed() ? 'Pagamento confirmado' : 'Modo de teste · não pagar pelo banco' }}</span>
            </div>
            @if (pixLoading()) { <p role="status">Consultando Pix...</p> }
            @if (pixError()) { <p class="field-error">{{ pixError() }}</p><button type="button" [disabled]="pixLoading() || loading()" (click)="load()">Tentar novamente</button> }
            @if (pix(); as payment) {
              @if (payment.status === 'PENDING' && !pixExpired() && payment.copyCode) {
                <div class="pix-layout">
                  @if (payment.qrImage) {
                    <div class="pix-qr"><img width="200" height="200" [src]="'data:image/png;base64,' + payment.qrImage" alt="QR Code do Pix de teste" /></div>
                  }
                  <div class="pix-instructions">
                    <h3>Aguardando confirmação</h3>
                    <p class="pix-amount">{{ info.total | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p>
                    <p>Válido até {{ payment.expiresAt | date:'dd/MM/yyyy HH:mm:ss' }}.</p>
                    <label for="pix-code">Pix Copia e Cola</label>
                    <div class="pix-copy"><input id="pix-code" [value]="payment.copyCode" readonly (focus)="selectCode($event)" /><button type="button" (click)="copy(payment.copyCode)">Copiar código Pix</button></div>
                    @if (copyMessage()) { <p role="status">{{ copyMessage() }}</p> }
                    <p class="muted">A confirmação é consultada automaticamente.</p>
                  </div>
                </div>
              }
              @if (payment.status === 'PAID') { <p class="pix-confirmed" role="status">Pagamento confirmado pelo provedor. O pedido está pago.</p> }
              @if (payment.status === 'REVIEW') { <p role="status">Pagamento em revisão. Aguarde a análise da loja antes de receber ou retirar os produtos.</p> }
              @if (payment.status === 'EXPIRED' || (payment.status === 'PENDING' && pixExpired())) { <p>O prazo do Pix terminou.</p> }
              @if (info.status === 'PENDING' && (!payment.status || payment.status === 'CREATING')) {
                <button type="button" [disabled]="pixLoading()" (click)="loadPix(true)">{{ payment.status === 'CREATING' || pixError() ? 'Retomar emissão do Pix de teste' : 'Gerar Pix de teste' }}</button>
              }
            }
          </section>
        }
        <div class="cart-layout"><section>
          @for (item of info.items; track item.variantId) {
            <app-record-card [staticCard]="true" [label]="item.name"><h3>{{ item.name }} {{ item.color }} {{ item.size }}</h3><p>{{ item.quantity }} unidade(s) × {{ item.unitPrice | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p><p>{{ item.unitPrice * item.quantity | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p></app-record-card>
          }
        </section><aside class="cart-summary"><h2>Resumo</h2><p>{{ info.customerName }} · {{ info.customerEmail }}</p>
          @if (info.delivery; as delivery) {
            <h3>Entrega</h3><p>{{ delivery.address.recipient }} · {{ delivery.address.street }}, {{ delivery.address.number }} {{ delivery.address.complement }} · {{ delivery.address.district }} · {{ delivery.address.city }}/{{ delivery.address.state }} · CEP {{ delivery.address.postalCode }}</p>
            <p>{{ delivery.phone }}</p>
            @if (delivery.shipping; as shipping) { <p>{{ shipping.name }} · {{ shipping.price | currency:'BRL':'symbol':'1.2-2':'pt-BR' }} · Prazo estimado: {{ shipping.deliveryDays }} dia(s)</p> }
            @else { <p>Este pedido antigo não possui frete registrado.</p> }
          }
          @if (info.pickup; as pickup) {
            <h3>Retirada na loja</h3><p>{{ pickup.address.street }}, {{ pickup.address.number }} {{ pickup.address.complement }} · {{ pickup.address.district }} · {{ pickup.address.city }}/{{ pickup.address.state }} · CEP {{ pickup.address.postalCode }}</p>
            <p>{{ pickup.phone }} · {{ pickup.instructions }}</p><p>Retirada: {{ pickup.cost | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p>
          }
          <p class="total">{{ info.total | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p><p>Total registrado pelo servidor.</p>
        </aside></div>
      }
    </main>`,
  styleUrls: ['./shop.css', './checkout.css', './order-page.css'],
})
export class OrderPageComponent {
  private readonly checkout = inject(CheckoutService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  private readonly destroy = inject(DestroyRef);
  private readonly router = inject(Router);
  protected readonly expiredNotice = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly order = signal<Order | null>(null);
  protected readonly pix = signal<PixPayment | null>(null);
  protected readonly pixLoading = signal(false);
  protected readonly pixError = signal('');
  protected readonly copyMessage = signal('');
  private readonly now = signal(Date.now());
  private deadlineChecked = false;
  constructor() {
    timer(0, 1000).pipe(takeUntilDestroyed(this.destroy)).subscribe(() => {
      this.now.set(Date.now());
      const order = this.order();
      if (order?.status === 'PENDING' && Date.parse(order.expiresAt) <= this.now() && !this.loading() && !this.pixLoading() && !this.deadlineChecked) {
        this.deadlineChecked = true;
        this.load();
      }
    });
    interval(20_000).pipe(takeUntilDestroyed(this.destroy)).subscribe(() => {
      if (['PENDING', 'PAYMENT_REVIEW', 'PAID'].includes(this.order()?.status ?? '') &&
          !this.loading() && !this.pixLoading() && !this.error() && !this.pixError()) this.load(true);
    });
    this.load();
  }
  protected pixExpired(): boolean {
    const deadline = this.pix()?.expiresAt;
    return !!deadline && Date.parse(deadline) <= this.now();
  }
  protected paymentConfirmed(): boolean { return this.pix()?.status === 'PAID'; }
  protected acknowledgeExpiry(): void {
    this.expiredNotice.set(false);
    this.checkout.beginNext();
    void this.router.navigateByUrl('/');
  }
  protected selectCode(event: FocusEvent): void {
    if (event.target instanceof HTMLInputElement) event.target.select();
  }
  protected status(value: string): string {
    return ({ PENDING: 'Pendente', EXPIRED: 'Expirado', CANCELLED: 'Cancelado', PAID: 'Pago',
      PAYMENT_REVIEW: 'Pagamento em revisão', DELIVERED: 'Entregue' } as Record<string, string>)[value] ?? value;
  }
  protected load(background = false): void {
    if (this.loading() || this.pixLoading()) return;
    this.loading.set(true); this.error.set('');
    if (!background) this.order.set(null);
    this.checkout.ownedOrder(this.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (order) => { this.order.set(order); this.loading.set(false); this.loadPix(); },
      error: (error: unknown) => {
        this.error.set(error instanceof HttpErrorResponse && error.status === 404
          ? 'Pedido não encontrado ou indisponível para esta conta nesta loja.' : checkoutError(error));
        this.loading.set(false);
      },
    });
  }
  protected loadPix(create = false): void {
    if (this.pixLoading()) return;
    this.pixLoading.set(true); this.pixError.set(''); this.copyMessage.set('');
    this.checkout.pix(this.id, create).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (payment) => {
        this.pix.set(payment); this.pixLoading.set(false);
        if (Date.parse(payment.expiresAt) > this.now()) this.deadlineChecked = false;
        this.order.update((order) => order && ({ ...order, expiresAt: payment.expiresAt,
          status: payment.status === 'REVIEW' ? 'PAYMENT_REVIEW' :
            payment.status === 'PAID' && order.status !== 'DELIVERED' ? 'PAID' : payment.status === 'EXPIRED' && order.status === 'PENDING' ? 'CANCELLED' : order.status }));
        if (this.order()?.status === 'CANCELLED' || this.order()?.status === 'EXPIRED') this.expiredNotice.set(true);
      },
      error: () => {
        if (!create) this.pix.set(null);
        this.pixLoading.set(false);
        this.pixError.set(create ? 'Não foi possível confirmar a emissão. Consulte novamente antes de repetir a tentativa.'
          : 'Não foi possível consultar o Pix. Tente novamente para conferir o pagamento.');
      },
    });
  }
  protected async copy(code: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(code);
      this.copyMessage.set('Código Pix copiado.');
    } catch {
      this.copyMessage.set('Selecione o campo Pix Copia e Cola para copiar manualmente.');
    }
  }
}
