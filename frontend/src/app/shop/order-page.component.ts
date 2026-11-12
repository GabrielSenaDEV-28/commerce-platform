import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { UI } from '../ui/ui';
import { Order } from './checkout.models';
import { CheckoutService, checkoutError } from './checkout.service';

@Component({
  selector: 'app-order-page',
  imports: [...UI, RouterLink, CurrencyPipe, DatePipe],
  template: `
    <header class="shop-header"><a class="brand" routerLink="/">c. <span>commerce</span></a><a routerLink="/conta/pedidos">Meus pedidos</a></header>
    <main class="shop-wrap">
      <nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/">Início</a><span>/</span><a routerLink="/conta/pedidos">Pedidos</a><span>/</span><span aria-current="page">Detalhes</span></nav>
      <h1>Seu pedido.</h1>
      @if (loading()) { <p role="status">Consultando pedido...</p> }
      @if (error()) { <p class="field-error">{{ error() }}</p><button type="button" [disabled]="loading()" (click)="load()">Consultar novamente</button> }
      @if (order(); as info) {
        <section class="checkout-panel"><h2>Pedido {{ info.id }}</h2><p>Estado atual: <strong>{{ status(info.status) }}</strong></p><p>Criado em {{ info.createdAt | date:'dd/MM/yyyy HH:mm' }} · Prazo da reserva: {{ info.expiresAt | date:'dd/MM/yyyy HH:mm' }}</p>
          <p>Pagamento online ainda não está disponível. Criar este pedido não confirma pagamento, venda ou liberação para entrega/retirada.</p>
          <button type="button" [disabled]="loading()" (click)="load()">Atualizar estado do pedido</button>
        </section>
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
  styleUrls: ['./shop.css', './checkout.css'],
})
export class OrderPageComponent {
  private readonly checkout = inject(CheckoutService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  private readonly destroy = inject(DestroyRef);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly order = signal<Order | null>(null);
  constructor() { this.load(); }
  protected status(value: string): string {
    return ({ PENDING: 'Pendente', EXPIRED: 'Expirado', CANCELLED: 'Cancelado' } as Record<string, string>)[value] ?? value;
  }
  protected load(): void {
    if (this.loading()) return;
    this.loading.set(true); this.error.set(''); this.order.set(null);
    this.checkout.ownedOrder(this.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (order) => { this.order.set(order); this.loading.set(false); },
      error: (error: unknown) => {
        this.error.set(error instanceof HttpErrorResponse && error.status === 404
          ? 'Pedido não encontrado ou indisponível para esta conta nesta loja.' : checkoutError(error));
        this.loading.set(false);
      },
    });
  }
}
