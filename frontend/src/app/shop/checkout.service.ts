import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { CartService } from './cart.service';
import { Checkout, CheckoutQuote, CreateOrder, Order, PickupOption } from './checkout.models';
import { CustomerAddress, CustomerProfile } from '../customer-account/customer.models';

@Injectable({ providedIn: 'root' })
export class CheckoutService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  readonly cart = inject(CartService);
  readonly reservation = signal<Checkout | null>(null);
  readonly pending = signal<{ reservationId: string; body: CreateOrder } | null>(null);
  readonly order = signal<Order | null>(null);
  constructor() {
    let owner = this.auth.customerIdentity();
    this.auth.sessionChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((reason) => {
      const current = this.auth.customerIdentity();
      if (reason === 'logout' || (reason === 'login' && current !== owner)) {
        this.reservation.set(null); this.order.set(null); this.pending.set(null);
      }
      if (reason !== 'expiry') owner = current;
    });
  }
  private customerHeaders(headers = new HttpHeaders()): HttpHeaders {
    const token = this.auth.customerAccessToken();
    if (!token) throw new Error('Entre com uma conta de cliente para continuar.');
    return headers.set('Authorization', `Bearer ${token}`);
  }
  profile(): Observable<CustomerProfile> { return this.http.get<CustomerProfile>('/api/public/customer/profile', { headers: this.customerHeaders() }); }
  addresses(): Observable<CustomerAddress[]> { return this.http.get<CustomerAddress[]>('/api/public/customer/addresses', { headers: this.customerHeaders() }); }
  pickup(): Observable<PickupOption> { return this.http.get<PickupOption>('/api/public/pickup'); }
  start(): Observable<Checkout> {
    return this.cart.exclusive(() => {
      if (this.pending()) return throwError(() => new Error('Confirme o resultado do pedido antes de iniciar outra reserva.'));
      return this.http.post<Checkout>('/api/public/checkout', {}, { headers: this.cart.headers() })
        .pipe(tap((value) => { this.reservation.set(value); this.order.set(null); }));
    });
  }
  beginNext(): void { this.order.set(null); this.reservation.set(null); }
  refresh(): Observable<Checkout> {
    return this.cart.exclusive(() => this.http.get<Checkout>('/api/public/checkout', { headers: this.cart.headers() })
      .pipe(tap((value) => this.reservation.set(value))));
  }
  cancel(): Observable<Checkout> {
    return this.cart.exclusive(() => {
      if (this.pending()) return throwError(() => new Error('Confirme o resultado do pedido antes de cancelar.'));
      const id = this.reservation()?.id;
      return this.http.post<Checkout>(`/api/public/checkout/${encodeURIComponent(id ?? '')}/cancel`, {}, { headers: this.cart.headers() })
        .pipe(tap((value) => this.reservation.set(value)));
    });
  }
  quote(postalCode: string): Observable<CheckoutQuote> {
    return this.cart.exclusive(() => this.http.post<CheckoutQuote>(
      `/api/public/checkout/${encodeURIComponent(this.reservation()?.id ?? '')}/shipping-estimates`,
      { postalCode }, { headers: this.cart.headers() }));
  }
  create(body: CreateOrder): Observable<Order> {
    return this.cart.exclusive(() => {
      const attempt = this.pending() ?? { reservationId: this.reservation()?.id ?? '', body: { ...body } };
      this.pending.set(attempt);
      return this.http.post<Order>(`/api/public/checkout/${encodeURIComponent(attempt.reservationId)}/orders`, attempt.body,
        { headers: this.customerHeaders(this.cart.headers()) }).pipe(
        tap((order) => { this.order.set(order); this.pending.set(null); }),
        catchError((error: unknown) => {
          // Transport/server failures may have committed; retry only the exact original request.
          if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500
            && ![401, 403, 408, 429].includes(error.status)) this.pending.set(null);
          return throwError(() => error);
        }),
      );
    });
  }
  ownedOrder(id: string): Observable<Order> {
    return this.http.get<Order>(`/api/public/customer/orders/${encodeURIComponent(id)}`, { headers: this.customerHeaders() });
  }
}

export function checkoutError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.error?.code === 'ITEMS_NOT_QUOTABLE') return 'Um ou mais itens não podem ser enviados. Consulte a loja sobre as medidas de envio ou escolha retirada, se disponível.';
    if (error.error?.code === 'SHIPPING_TEMPORARILY_UNAVAILABLE') return 'O serviço de frete está temporariamente indisponível. Tente consultar o resultado novamente mais tarde.';
    if (error.status === 401) return 'Sua sessão expirou. Entre novamente para continuar.';
    if (error.status === 403) return 'Utilize uma conta de cliente desta loja para continuar.';
    if (error.status === 404) return 'A sacola, a reserva ou o endereço não está mais disponível. Confira seus dados.';
    if (error.status === 409) return 'As condições do checkout mudaram. Confira a reserva, o telefone do perfil e a entrega antes de continuar.';
    if (error.status === 429) return 'Muitas tentativas. Aguarde um pouco antes de continuar.';
  }
  return 'Não foi possível confirmar a operação. Confira sua conexão e consulte o resultado antes de repetir.';
}
