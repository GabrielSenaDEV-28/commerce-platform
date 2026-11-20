import { DOCUMENT, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { AuthService, safeReturnUrl } from '../auth/auth.service';
import { CheckoutPageComponent } from './checkout-page.component';
import { LoginComponent } from '../login/login.component';
import { CustomerAccountComponent } from '../customer-account/customer-account.component';
import { StorefrontComponent } from '../storefront/storefront.component';

describe('checkout access and navigation', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter(routes.map((route) => route.component === StorefrontComponent ? { ...route, canMatch: undefined } : route)), provideHttpClient(), provideHttpClientTesting()] });
    TestBed.overrideComponent(LoginComponent, { add: { providers: [{ provide: DOCUMENT, useValue: {
      location: { hostname: 'loja.localhost' }, defaultView: window,
    } }] } });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('requires customer login before starting any reservation and preserves the checkout destination', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/checkout');
    expect(TestBed.inject(Router).url).toBe('/entrar?returnUrl=%2Fcheckout');
    http.expectNone('/api/public/checkout');
  });
  it('loads the checkout lazily for a customer without creating a reservation', async () => {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/checkout', CheckoutPageComponent);
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', email: 'customer@example.com', phone: null });
    http.expectOne('/api/public/customer/addresses').flush([]);
    http.expectOne('/api/public/pickup').flush({ available: false, address: null, instructions: null });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Sua sacola está vazia');
    http.expectNone('/api/public/checkout');
  });
  it('only accepts local checkout and order return destinations for customer identities', () => {
    expect(safeReturnUrl('/checkout', 'customer')).toBe('/checkout');
    expect(safeReturnUrl('/conta/pedidos/o1', 'customer')).toBe('/conta/pedidos/o1');
    expect(safeReturnUrl('/checkout', 'merchant')).toBeNull();
    expect(safeReturnUrl('//example.com/checkout', 'customer')).toBeNull();
    expect(safeReturnUrl('/checkout/another', 'customer')).toBeNull();
  });
  it('returns to checkout with the selected items after login', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/entrar?returnUrl=%2Fcheckout%3Fitem%3Dv1', LoginComponent);
    for (const [id, value] of [['email', 'customer@example.com'], ['password', 'password']]) {
      const input = harness.routeNativeElement!.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value; input.dispatchEvent(new Event('input'));
    }
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/checkout?item=v1');
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', phone: '11999999999' });
    http.expectOne('/api/public/customer/addresses').flush([]);
    http.expectOne('/api/public/pickup').flush({ available: false });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
  });
  it('links back to checkout from the existing address manager without duplicating its form', async () => {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/conta/enderecos?returnTo=checkout', CustomerAccountComponent);
    http.expectOne('/api/public/customer/addresses').flush([]); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('a[href="/checkout?returning=1"]')!.textContent).toContain('Voltar ao checkout');
  });
  it('saves a delivery address from checkout and returns to the same selection', async () => {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', expiresIn: 900 });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/conta/enderecos?returnTo=checkout&novo=1&item=v1', CustomerAccountComponent);
    http.expectOne('/api/public/customer/addresses').flush([]);
    harness.detectChanges();
    for (const [id, value] of Object.entries({ recipient: 'Cliente', postal: '01001-000', street: 'Praça da Sé', number: '1', district: 'Sé', city: 'São Paulo', state: 'SP' })) {
      const field = harness.routeNativeElement!.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
      field.value = value;
      field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
    }
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/public/customer/addresses');
    expect(request.request.body.postalCode).toBe('01001000');
    const address = { ...request.request.body, id: 'a1' };
    request.flush(address);
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/checkout?item=v1&address=a1&returning=1');
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', phone: '11999999999' });
    http.expectOne('/api/public/customer/addresses').flush([address]);
    http.expectOne('/api/public/pickup').flush({ available: false });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [{ productId: 'p1', variantId: 'v1', name: 'Camisa', quantity: 1, price: 20, available: true }] } });
    await harness.fixture.whenStable();
    http.expectOne(req => req.method === 'GET' && req.url === '/api/public/checkout').flush({ status: 'ACTIVE', id: 'r1', expiresAt: '2099-01-01T00:00:00Z', items: [{ productId: 'p1', variantId: 'v1', quantity: 1, price: 20, reserved: true }] });
    await harness.fixture.whenStable();
    const shipping = http.expectOne('/api/public/checkout/r1/shipping-estimates');
    expect(shipping.request.body).toEqual({ postalCode: '01001000' });
    shipping.flush({ quoteId: 'q1', reservationId: 'r1', expiresAt: '2099-01-01T00:00:00Z', quotes: [{ id: 1, name: 'PAC', price: 10, deliveryDays: 4 }], unavailable: [] });
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector<HTMLSelectElement>('#checkout-address')?.value).toBe('a1');
    expect(harness.routeNativeElement!.textContent).toContain('PAC');
  });
});
