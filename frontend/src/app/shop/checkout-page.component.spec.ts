import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { CartService } from './cart.service';
import { CheckoutService } from './checkout.service';
import { CheckoutPageComponent } from './checkout-page.component';

describe('checkout review', () => {
  let http: HttpTestingController;
  const item = { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 35, quantity: 2, stockQuantity: 8, available: true };
  const cart = { expiresAt: '2099-01-01T00:00:00Z', items: [item] };
  const reserved = { productId: 'p1', variantId: 'v1', quantity: 2, price: 36, reserved: true, availableQuantity: 8 };
  const address = { id: 'a1', recipient: 'Cliente', postalCode: '01001000', street: 'Praça', number: '1', complement: '', district: 'Centro', city: 'São Paulo', state: 'SP', country: 'BR' };
  const quote = { quoteId: 'q1', reservationId: 'r1', expiresAt: '2099-01-01T00:00:00Z', quotes: [{ id: 1, name: 'PAC', price: 10, deliveryDays: 4 }], unavailable: [] };
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); vi.useRealTimers(); });
  function setup(options: { phone?: string | null; pickup?: boolean; empty?: boolean } = {}) {
    if (!options.empty) {
      TestBed.inject(CartService).add('p1', 'v1', 2).subscribe();
      http.expectOne('/api/public/cart/customer').flush({ token: 'opaque', cart: { items: [] } });
      http.expectOne('/api/public/cart').flush({ ...cart, items: [] });
      http.expectOne('/api/public/cart/items/p1/v1').flush(cart);
    }
    const fixture = TestBed.createComponent(CheckoutPageComponent);
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', email: 'customer@example.com', phone: options.phone === undefined ? '11987654321' : options.phone });
    http.expectOne('/api/public/customer/addresses').flush([address, { ...address, id: 'a2', postalCode: '20010000' }]);
    http.expectOne('/api/public/pickup').flush({ available: options.pickup ?? false, address: options.pickup ? address : null, instructions: 'Aguarde a loja.' });
    if (!options.empty) http.expectOne('/api/public/cart').flush(cart);
    else http.expectOne('/api/public/cart/customer').flush({ token: 'opaque', cart: { items: [] } });
    fixture.detectChanges(); return fixture;
  }
  function button(fixture: { nativeElement: HTMLElement }, label: string) {
    return [...fixture.nativeElement.querySelectorAll<HTMLButtonElement>('button')].find((element) => element.textContent?.includes(label))!;
  }
  function start(fixture: ReturnType<typeof setup>, partial = false, expiresAt = '2099-01-01T00:00:00Z') {
    button(fixture, 'Conferir e reservar').click();
    http.expectOne('/api/public/checkout').flush({ id: 'r1', status: 'ACTIVE', expiresAt, items: partial ? [reserved, { ...reserved, variantId: 'v2', reserved: false }] : [reserved] });
    fixture.detectChanges();
  }
  function selectAddress(fixture: ReturnType<typeof setup>, id = 'a1') {
    const select = fixture.nativeElement.querySelector('#checkout-address') as HTMLSelectElement;
    select.value = id; select.dispatchEvent(new Event('change')); fixture.detectChanges();
  }
  function shipping(fixture: ReturnType<typeof setup>) {
    selectAddress(fixture);
    button(fixture, 'Consultar opções').click();
    http.expectOne('/api/public/checkout/r1/shipping-estimates').flush(quote); fixture.detectChanges();
    fixture.nativeElement.querySelector('input[name="shipping"]').click(); fixture.detectChanges();
  }
  it('does not reserve on entry and blocks an empty cart or incomplete profile', () => {
    const fixture = setup({ phone: null });
    expect(button(fixture, 'Conferir e reservar').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Cadastre um telefone');
    expect(fixture.nativeElement.querySelector('a[href="/conta/perfil?returnTo=checkout"]')).not.toBeNull();
    http.expectNone('/api/public/checkout');
  });
  it('shows an empty state without creating a cart or reservation', () => {
    const fixture = setup({ empty: true });
    expect(fixture.nativeElement.textContent).toContain('Sua sacola está vazia');
    http.expectNone('/api/public/cart'); http.expectNone('/api/public/checkout');
  });
  it('uses reservation prices, validates fields locally, and creates a delivery order only after selection', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = setup(); start(fixture);
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('72,00');
    button(fixture, 'Criar pedido pendente').click(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Selecione um endereço');
    expect(fixture.nativeElement.textContent).toContain('selecione uma opção de entrega válida');
    http.expectNone('/api/public/checkout/r1/orders');
    shipping(fixture);
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('82,00');
    button(fixture, 'Criar pedido pendente').click();
    const create = http.expectOne('/api/public/checkout/r1/orders');
    expect(create.request.body).toEqual({ acceptPartial: false, fulfillmentType: 'DELIVERY', addressId: 'a1', quoteId: 'q1', shippingServiceId: 1 });
    expect(navigate).not.toHaveBeenCalled();
    create.flush({ id: 'o1' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/conta/pedidos/o1');
  });
  it('requires explicit consent for partial checkout and excludes unreserved items from the total', () => {
    const fixture = setup({ pickup: true }); start(fixture, true);
    const pickup = fixture.nativeElement.querySelectorAll('input[name="fulfillment"]')[1] as HTMLInputElement;
    pickup.click(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Retirada gratuita');
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('72,00');
    button(fixture, 'Criar pedido pendente').click(); fixture.detectChanges();
    http.expectNone('/api/public/checkout/r1/orders');
    expect(fixture.nativeElement.textContent).toContain('Confirme a compra parcial');
    fixture.nativeElement.querySelector('input[type="checkbox"]').click(); fixture.detectChanges();
    button(fixture, 'Criar pedido pendente').click();
    const create = http.expectOne('/api/public/checkout/r1/orders');
    expect(create.request.body).toEqual({ acceptPartial: true, fulfillmentType: 'PICKUP', addressId: null, quoteId: null, shippingServiceId: null });
    create.flush({}, { status: 409, statusText: 'Conflict' });
  });
  it('invalidates shipping on address changes and demands a new selection after a changed quote', () => {
    const fixture = setup(); start(fixture); shipping(fixture);
    selectAddress(fixture, 'a2');
    expect(fixture.nativeElement.querySelector('input[name="shipping"]')).toBeNull();
    shipping(fixture);
    button(fixture, 'Criar pedido pendente').click();
    http.expectOne('/api/public/checkout/r1/orders').flush({ code: 'SHIPPING_QUOTE_CHANGED', quote: { ...quote, quoteId: 'q2' } }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('O valor ou prazo do frete mudou');
    expect(fixture.nativeElement.querySelector('input[name="shipping"]')).toBeNull();
    button(fixture, 'Criar pedido pendente').click();
    http.expectNone('/api/public/checkout/r1/orders');
  });
  it('keeps the original order request after a network error and prevents editing until recovery', () => {
    const fixture = setup(); start(fixture); shipping(fixture);
    button(fixture, 'Criar pedido pendente').click();
    const create = http.expectOne('/api/public/checkout/r1/orders');
    const original = create.request.body;
    create.error(new ProgressEvent('error')); fixture.detectChanges();
    expect(fixture.componentInstance.hasUnconfirmedOperation()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Precisamos confirmar o resultado');
    expect(fixture.nativeElement.querySelector('#checkout-address')).toBeNull();
    button(fixture, 'Confirmar resultado').click();
    const retry = http.expectOne('/api/public/checkout/r1/orders');
    expect(retry.request.body).toEqual(original);
    retry.flush({}, { status: 409, statusText: 'Conflict' });
  });
  it('warns before cancellation and returns to the cart only after releasing the reservation', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = setup(); start(fixture);
    button(fixture, 'Cancelar reserva').click(); fixture.detectChanges();
    http.expectNone('/api/public/checkout/r1/cancel');
    expect(fixture.nativeElement.textContent).toContain('não pode garantir o estoque');
    button(fixture, 'Liberar reserva').click();
    http.expectOne('/api/public/checkout/r1/cancel').flush({ id: 'r1', status: 'CANCELLED', items: [] });
    await fixture.whenStable();
    expect(TestBed.inject(CartService).busy()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/carrinho');
  });
  it('blocks expired reservations locally and preserves the cart for a new explicit attempt', () => {
    const fixture = setup(); start(fixture, false, '2000-01-01T00:00:00Z');
    expect(button(fixture, 'Criar pedido pendente').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('A reserva não está ativa');
    expect(button(fixture, 'Conferir e reservar').disabled).toBe(false);
    expect(TestBed.inject(CartService).count()).toBe(2);
  });
  it('can reconcile a cancellation whose response was lost without waiting for local expiry', () => {
    const fixture = setup(); start(fixture);
    button(fixture, 'Cancelar reserva').click(); fixture.detectChanges();
    button(fixture, 'Liberar reserva').click();
    http.expectOne('/api/public/checkout/r1/cancel').error(new ProgressEvent('error')); fixture.detectChanges();
    button(fixture, 'Conferir estado da reserva').click();
    http.expectOne('/api/public/checkout').flush({ id: 'r1', status: 'CANCELLED', expiresAt: '2099-01-01T00:00:00Z', items: [reserved] });
    fixture.detectChanges();
    expect(button(fixture, 'Criar pedido pendente').disabled).toBe(true);
    expect(button(fixture, 'Conferir e reservar')).toBeDefined();
  });
  it('does not invent shipping alternatives when the provider fails', () => {
    const fixture = setup(); start(fixture); selectAddress(fixture);
    button(fixture, 'Consultar opções').click();
    http.expectOne('/api/public/checkout/r1/shipping-estimates').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[name="shipping"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.field-error')).not.toBeNull();
    expect((fixture.nativeElement.querySelectorAll('input[name="fulfillment"]')[1] as HTMLInputElement).disabled).toBe(true);
    expect(TestBed.inject(CheckoutService).pending()).toBeNull();
  });
  it('disables submission at the exact reservation deadline without refreshing away the cart token', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const fixture = setup();
    start(fixture, false, '2026-01-01T10:00:02Z');
    expect(button(fixture, 'Criar pedido pendente').disabled).toBe(false);
    vi.advanceTimersByTime(2000); fixture.detectChanges();
    expect(button(fixture, 'Criar pedido pendente').disabled).toBe(true);
    expect(TestBed.inject(CartService).headers().get('Cart-Token')).toBe('opaque');
  });
  it('requires a new quote after its exact deadline even while the reservation remains active', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const fixture = setup(); start(fixture); selectAddress(fixture);
    button(fixture, 'Consultar opções').click();
    http.expectOne('/api/public/checkout/r1/shipping-estimates').flush({ ...quote, expiresAt: '2026-01-01T10:00:02Z' }); fixture.detectChanges();
    fixture.nativeElement.querySelector('input[name="shipping"]').click(); fixture.detectChanges();
    vi.advanceTimersByTime(2000); fixture.detectChanges();
    button(fixture, 'Criar pedido pendente').click(); fixture.detectChanges();
    http.expectNone('/api/public/checkout/r1/orders');
    expect(fixture.nativeElement.textContent).toContain('A cotação expirou');
  });
  it('lets an expired completed checkout begin a new review rather than permanently showing the old order', () => {
    const fixture = setup();
    const service = TestBed.inject(CheckoutService);
    service.reservation.set({ id: 'old', status: 'EXPIRED', expiresAt: '2000-01-01T00:00:00Z', items: [] });
    service.order.set({ id: 'old-order', reservationId: 'old', customerName: 'Cliente', customerEmail: 'customer@example.com', status: 'EXPIRED', createdAt: '2000-01-01T00:00:00Z', expiresAt: '2000-01-01T00:20:00Z', total: 0, items: [], fulfillmentType: 'PICKUP', delivery: null, pickup: null });
    fixture.detectChanges();
    button(fixture, 'Conferir sacola para um novo pedido').click();
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', email: 'customer@example.com', phone: '11987654321' });
    http.expectOne('/api/public/customer/addresses').flush([]);
    http.expectOne('/api/public/pickup').flush({ available: false, address: null });
    http.expectOne('/api/public/cart').flush(cart); fixture.detectChanges();
    expect(service.order()).toBeNull();
    expect(button(fixture, 'Conferir e reservar')).toBeDefined();
    http.expectNone('/api/public/checkout');
  });
});
