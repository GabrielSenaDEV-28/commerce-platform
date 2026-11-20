import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, finalize, forkJoin, interval, throwError } from 'rxjs';
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
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly profile = signal<CustomerProfile | null>(null);
  protected readonly addresses = signal<CustomerAddress[]>([]);
  protected readonly pickup = signal<PickupOption | null>(null);
  protected readonly fulfillment = signal<'DELIVERY' | 'PICKUP'>('DELIVERY');
  protected readonly addressId = signal('');
  protected readonly variantIds = signal(this.route.snapshot.queryParamMap.getAll('item'));
  protected readonly reservationConflict = computed(() => {
    const selection = this.variantIds();
    const items = this.checkout.reservation()?.items ?? [];
    return this.active() && selection.length > 0 &&
      (selection.length !== items.length || items.some((item) => !selection.includes(item.variantId)));
  });
  protected readonly selectedCartItems = computed(() => {
    const selection = this.variantIds();
    return (this.checkout.cart.cart()?.items ?? []).filter((item) => !selection.length || selection.includes(item.variantId));
  });
  protected readonly quote = signal<CheckoutQuote | null>(null);
  protected readonly quoting = signal(false);
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
  protected readonly selectedService = computed(() => this.quoteValid() ? this.quote()?.quotes.find((service) => service.id === this.serviceId()) : undefined);
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
      .pipe(finalize(() => { this.loading.set(false); if (this.profile()) this.scheduleQuote(); }), takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ profile, addresses, pickup, cart }) => {
          this.profile.set(profile); this.addresses.set(addresses); this.pickup.set(pickup);
          const preferred = this.route.snapshot.queryParamMap.get('address') ?? this.addressId();
          this.addressId.set(addresses.find((address) => address.id === preferred)?.id ?? (addresses.length === 1 ? addresses[0].id : ''));
          if (!pickup.available) this.fulfillment.set('DELIVERY');
          if (cart && this.route.snapshot.queryParamMap.has('returning') && !this.checkout.reservation()) {
            queueMicrotask(() => { if (!this.destroy.destroyed) this.restoreReservation(); });
          }
        },
        error: (error: unknown) => this.error.set(checkoutError(error)),
      });
  }
  protected start(): void {
    if (this.isSaving() || this.checkout.pending()) return;
    this.resetSelection(); this.partial.set(false); this.submitted.set(false);
    const ids = this.variantIds();
    this.run(this.checkout.start(ids.length ? ids : undefined).pipe(catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 409 && ids.length) {
        queueMicrotask(() => { if (!this.destroy.destroyed) this.restoreReservation(); });
      }
      return throwError(() => error);
    })), () => this.scheduleQuote());
  }
  private restoreReservation(): void {
    this.checkout.refresh().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => this.scheduleQuote(),
      error: (error: unknown) => {
        if (!(error instanceof HttpErrorResponse) || error.status !== 404) this.error.set(checkoutError(error));
      },
    });
  }
  protected useExistingSelection(): void {
    const ids = this.checkout.reservation()?.items.map((item) => item.variantId) ?? [];
    this.variantIds.set(ids);
    this.error.set('');
    this.scheduleQuote();
    void this.router.navigate([], { relativeTo: this.route, queryParamsHandling: 'merge', queryParams: { item: ids }, replaceUrl: true });
  }
  protected nextOrder(): void {
    if (this.isSaving() || this.active() || this.checkout.pending()) return;
    this.checkout.beginNext(); this.resetSelection(); this.partial.set(false); this.submitted.set(false);
    if (this.variantIds().length) void this.router.navigateByUrl('/carrinho');
    else this.load();
  }
  protected refreshReservation(): void {
    if (this.isSaving() || this.checkout.pending()) return;
    this.resetSelection(); this.cancelRequested.set(false);
    this.run(this.checkout.refresh(), () => this.scheduleQuote());
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
    if (value === 'DELIVERY') this.calculate();
  }
  protected chooseAddress(event: Event): void {
    if (event.target instanceof HTMLSelectElement) {
      this.addressId.set(event.target.value); this.resetSelection(); this.calculate();
    }
  }
  protected confirmPartial(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.partial.set(event.target.checked);
  }
  private resetSelection(): void { this.quote.set(null); this.serviceId.set(null); }
  private scheduleQuote(): void {
    if (!this.selectedAddress() || !this.active() || this.fulfillment() !== 'DELIVERY') return;
    queueMicrotask(() => {
      if (!this.destroy.destroyed && !this.quote() && this.fulfillment() === 'DELIVERY' && !this.checkout.order()) this.calculate();
    });
  }
  protected calculate(): void {
    const address = this.selectedAddress();
    if (this.isSaving() || this.fulfillment() !== 'DELIVERY' || !this.active() || this.reservationConflict() || !address || this.checkout.pending()) return;
    this.resetSelection();
    this.quoting.set(true);
    this.run(this.checkout.quote(address.postalCode).pipe(finalize(() => this.quoting.set(false))), (quote) => this.quote.set(quote));
  }
  protected submit(): void {
    if (this.isSaving()) return;
    this.submitted.set(true);
    const pending = this.checkout.pending();
    if (!pending && (!this.active() || this.reservationConflict() || !this.profile()?.phone || (this.partialRequired() && !this.partial())
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
