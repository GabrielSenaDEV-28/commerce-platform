import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Observable, finalize, forkJoin, interval } from 'rxjs';
import { watchUnsavedChanges } from '../unsaved-changes';
import { UI } from '../ui/ui';
import { CheckoutQuote, CreateOrder, PickupOption, ReservationItem } from './checkout.models';
import { CustomerAddress, CustomerProfile } from '../customer-account/customer.models';
import { CheckoutService, checkoutError } from './checkout.service';

@Component({
  selector: 'app-checkout-page',
  imports: [...UI, RouterLink, CurrencyPipe, DatePipe],
  templateUrl: './checkout-page.component.html',
  styleUrls: ['./shop.css', './checkout.css'],
})
export class CheckoutPageComponent {
  protected readonly checkout = inject(CheckoutService);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly profile = signal<CustomerProfile | null>(null);
  protected readonly addresses = signal<CustomerAddress[]>([]);
  protected readonly pickup = signal<PickupOption | null>(null);
  protected readonly fulfillment = signal<'DELIVERY' | 'PICKUP'>('DELIVERY');
  protected readonly addressId = signal('');
  protected readonly quote = signal<CheckoutQuote | null>(null);
  protected readonly serviceId = signal<number | null>(null);
  protected readonly partial = signal(false);
  protected readonly submitted = signal(false);
  protected readonly cancelRequested = signal(false);
  protected readonly now = signal(Date.now());
  protected readonly active = computed(() => {
    const reservation = this.checkout.reservation();
    return reservation?.status === 'ACTIVE' && !!reservation.expiresAt && Date.parse(reservation.expiresAt) > this.now();
  });
  protected readonly partialRequired = computed(() => this.checkout.reservation()?.items.some((item) => !item.reserved) ?? false);
  protected readonly selectedAddress = computed(() => this.addresses().find((address) => address.id === this.addressId()));
  protected readonly selectedService = computed(() => this.quote()?.quotes.find((service) => service.id === this.serviceId()));
  protected readonly subtotal = computed(() => this.checkout.reservation()?.items.reduce((sum, item) => sum + (item.reserved ? item.price * item.quantity : 0), 0) ?? 0);
  protected readonly total = computed(() => this.subtotal() + (this.fulfillment() === 'DELIVERY' ? this.selectedService()?.price ?? 0 : 0));
  protected readonly quoteValid = computed(() => !!this.quote() && Date.parse(this.quote()!.expiresAt) > this.now());

  constructor() {
    watchUnsavedChanges(this);
    interval(1000).pipe(takeUntilDestroyed(this.destroy)).subscribe(() => this.now.set(Date.now()));
    this.load();
  }
  hasUnsavedChanges(): boolean { return false; }
  hasUnconfirmedOperation(): boolean { return !!this.checkout.pending(); }
  isSaving(): boolean { return this.checkout.cart.busy() || this.loading(); }
  protected name(item: ReservationItem): string {
    const cartItem = this.checkout.cart.cart()?.items.find((value) => value.variantId === item.variantId && value.productId === item.productId);
    return cartItem ? [cartItem.name, cartItem.color, cartItem.size].filter(Boolean).join(' · ') : `Produto ${item.productId} · variante ${item.variantId}`;
  }
  protected load(): void {
    if (this.isSaving()) return;
    this.loading.set(true); this.error.set(''); this.profile.set(null); this.resetSelection();
    forkJoin({ profile: this.checkout.profile(), addresses: this.checkout.addresses(), pickup: this.checkout.pickup(), cart: this.checkout.cart.load() })
      .pipe(finalize(() => this.loading.set(false)), takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ profile, addresses, pickup }) => {
          this.profile.set(profile); this.addresses.set(addresses); this.pickup.set(pickup);
          if (!addresses.some((address) => address.id === this.addressId())) this.addressId.set('');
          if (!pickup.available) this.fulfillment.set('DELIVERY');
        },
        error: (error: unknown) => this.error.set(checkoutError(error)),
      });
  }
  protected start(): void {
    if (this.isSaving() || this.checkout.pending()) return;
    this.resetSelection(); this.partial.set(false); this.submitted.set(false);
    this.run(this.checkout.start());
  }
  protected nextOrder(): void {
    if (this.isSaving() || this.active() || this.checkout.pending()) return;
    this.checkout.beginNext(); this.resetSelection(); this.partial.set(false); this.submitted.set(false);
    this.load();
  }
  protected refreshReservation(): void {
    if (this.isSaving() || this.checkout.pending()) return;
    this.resetSelection(); this.cancelRequested.set(false);
    this.run(this.checkout.refresh());
  }
  protected cancel(): void {
    if (this.isSaving() || this.checkout.pending()) return;
    this.run(this.checkout.cancel(), () => {
      this.resetSelection();
      queueMicrotask(() => { if (!this.destroy.destroyed) void this.router.navigateByUrl('/carrinho'); });
    });
  }
  protected chooseFulfillment(value: 'DELIVERY' | 'PICKUP'): void {
    this.fulfillment.set(value); this.resetSelection();
  }
  protected chooseAddress(event: Event): void {
    if (event.target instanceof HTMLSelectElement) { this.addressId.set(event.target.value); this.resetSelection(); }
  }
  protected confirmPartial(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.partial.set(event.target.checked);
  }
  private resetSelection(): void { this.quote.set(null); this.serviceId.set(null); }
  protected calculate(): void {
    const address = this.selectedAddress();
    if (this.isSaving() || !this.active() || !address || this.checkout.pending()) return;
    this.resetSelection();
    this.run(this.checkout.quote(address.postalCode), (quote) => this.quote.set(quote));
  }
  protected submit(): void {
    if (this.isSaving()) return;
    this.submitted.set(true);
    const pending = this.checkout.pending();
    if (!pending && (!this.active() || !this.profile()?.phone || (this.partialRequired() && !this.partial())
      || (this.fulfillment() === 'PICKUP' ? !this.pickup()?.available
        : !this.selectedAddress() || !this.selectedService() || !this.quoteValid()))) return;
    const body: CreateOrder = pending?.body ?? {
      acceptPartial: this.partial(), fulfillmentType: this.fulfillment(),
      addressId: this.fulfillment() === 'DELIVERY' ? this.addressId() : null,
      quoteId: this.fulfillment() === 'DELIVERY' ? this.quote()!.quoteId : null,
      shippingServiceId: this.fulfillment() === 'DELIVERY' ? this.serviceId() : null,
    };
    this.run(this.checkout.create(body), (order) => {
      queueMicrotask(() => { if (!this.destroy.destroyed) void this.router.navigateByUrl(`/conta/pedidos/${encodeURIComponent(order.id)}`); });
    });
  }
  private run<T>(request: Observable<T>, next?: (value: T) => void): void {
    this.error.set('');
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (value) => next?.(value),
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 409 && error.error?.code === 'SHIPPING_QUOTE_CHANGED') {
          this.resetSelection();
          this.error.set('O valor ou prazo do frete mudou. Consulte as opções novamente, selecione a entrega e confira o total antes de criar o pedido.');
        } else this.error.set(checkoutError(error));
      },
    });
  }
}
