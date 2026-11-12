import { DOCUMENT } from '@angular/common';
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
  it('opens the storefront after login even when the previous destination was checkout', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/entrar?returnUrl=%2Fcheckout', LoginComponent);
    expect(harness.routeNativeElement!.querySelector('a[href="/cadastro?returnUrl=%2Fcheckout"]')).not.toBeNull();
    for (const [id, value] of [['email', 'customer@example.com'], ['password', 'password']]) {
      const input = harness.routeNativeElement!.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value; input.dispatchEvent(new Event('input'));
    }
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/');
    http.expectOne((req) => req.url === '/api/public/catalog/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
    http.expectNone('/api/public/customer/profile');
  });
  it('links back to checkout from the existing address manager without duplicating its form', async () => {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/conta/enderecos?returnTo=checkout', CustomerAccountComponent);
    http.expectOne('/api/public/customer/addresses').flush([]); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('a[href="/checkout"]')!.textContent).toContain('Voltar ao checkout');
  });
});
