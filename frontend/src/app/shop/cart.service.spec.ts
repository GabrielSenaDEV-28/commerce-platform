import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CartService } from './cart.service';
import { Cart } from './shop.models';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';

const testCart: Cart = { expiresAt: '2026-12-01T00:00:00Z', items: [
  { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 35, quantity: 2, stockQuantity: 8, available: true },
] };

describe('shopping cart', () => {
  let http: HttpTestingController;
  let cart: CartService;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController); cart = TestBed.inject(CartService);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  function create() {
    cart.add('p1', 'v1', 2).subscribe();
    http.expectOne('/api/public/cart').flush({ token: 'opaque-cart-token' });
    const read = http.expectOne('/api/public/cart');
    expect(read.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    read.flush({ ...testCart, items: [] });
    const write = http.expectOne('/api/public/cart/items/p1/v1');
    expect(write.request.body).toEqual({ quantity: 2 });
    write.flush(testCart);
  }
  it('does not create carts just to display an empty bag', () => {
    const next = vi.fn(); cart.load().subscribe(next);
    expect(next).toHaveBeenCalledWith(null);
    http.expectNone('/api/public/cart');
  });
  it('recovers the anonymous bag after reloading the same tab using its saved token', () => {
    create();
    const reloaded = TestBed.runInInjectionContext(() => new CartService());
    const result = vi.fn(); reloaded.load().subscribe(result);
    const read = http.expectOne('/api/public/cart');
    expect(read.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    read.flush(testCart);
    expect(cart.count()).toBe(2); expect(reloaded.count()).toBe(2);
    expect(result).toHaveBeenCalledWith(testCart);
  });
  it('creates once, uses its token and adds to the current server quantity', () => {
    create();
    expect(cart.count()).toBe(2); expect(cart.busy()).toBe(false);
    cart.add('p1', 'v1', 3).subscribe();
    http.expectOne('/api/public/cart').flush(testCart);
    const write = http.expectOne('/api/public/cart/items/p1/v1');
    expect(write.request.body).toEqual({ quantity: 5 });
    expect(write.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    write.flush({ ...testCart, items: [{ ...testCart.items[0], quantity: 5 }] });
    expect(cart.count()).toBe(5);
  });
  it('serializes mutations and sends absolute quantities for safe updates', () => {
    create();
    cart.setQuantity('p1', 'v1', 4).subscribe();
    const blocked = vi.fn(); cart.remove('p1', 'v1').subscribe({ error: blocked });
    expect(blocked).toHaveBeenCalled();
    const write = http.expectOne('/api/public/cart/items/p1/v1');
    expect(write.request.method).toBe('PUT'); expect(write.request.body).toEqual({ quantity: 4 });
    write.flush({ ...testCart, items: [{ ...testCart.items[0], quantity: 4 }] });
    cart.remove('p1', 'v1').subscribe();
    const remove = http.expectOne('/api/public/cart/items/p1/v1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush({ ...testCart, items: [] });
    expect(cart.count()).toBe(0);
  });
  it('does not retry uncertain writes and keeps the token for a server reconciliation', () => {
    create();
    const error = vi.fn(); cart.setQuantity('p1', 'v1', 3).subscribe({ error });
    http.expectOne('/api/public/cart/items/p1/v1').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(error).toHaveBeenCalled(); expect(cart.busy()).toBe(false);
    http.expectNone('/api/public/cart/items/p1/v1');
    cart.load().subscribe();
    http.expectOne('/api/public/cart').flush({ ...testCart, items: [{ ...testCart.items[0], quantity: 3 }] });
    expect(cart.count()).toBe(3);
  });
  it('removes all variants of a product sequentially and preserves other products', () => {
    create();
    const second = { ...testCart.items[0], variantId: 'v2' };
    const other = { ...testCart.items[0], productId: 'p2' };
    const next = vi.fn();
    cart.removeProduct('p1').subscribe(next);
    http.expectOne('/api/public/cart').flush({ ...testCart, items: [...testCart.items, second, other] });
    expect(cart.busy()).toBe(true);
    http.expectNone('/api/public/cart/items/p1/v2');
    http.expectOne('/api/public/cart/items/p1/v1').flush({ ...testCart, items: [second, other] });
    expect(next).not.toHaveBeenCalled();
    http.expectOne('/api/public/cart/items/p1/v2').flush({ ...testCart, items: [other] });
    expect(next).toHaveBeenCalledOnce();
    expect(cart.contains('p1')).toBe(false); expect(cart.contains('p2')).toBe(true);
    expect(cart.busy()).toBe(false);
  });
  it('keeps confirmed partial removals visible, stops on error and reconciles before retrying', () => {
    create();
    const second = { ...testCart.items[0], variantId: 'v2' };
    const third = { ...testCart.items[0], variantId: 'v3' };
    const failed = vi.fn(); const next = vi.fn();
    cart.removeProduct('p1').subscribe({ next, error: failed });
    http.expectOne('/api/public/cart').flush({ ...testCart, items: [...testCart.items, second, third] });
    http.expectOne('/api/public/cart/items/p1/v1').flush({ ...testCart, items: [second, third] });
    http.expectOne('/api/public/cart/items/p1/v2').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectNone('/api/public/cart/items/p1/v3');
    expect(failed).toHaveBeenCalledOnce(); expect(next).not.toHaveBeenCalled();
    expect(cart.cart()?.items).toEqual([second, third]); expect(cart.busy()).toBe(false);
    cart.removeProduct('p1').subscribe();
    http.expectOne('/api/public/cart').flush({ ...testCart, items: [third] });
    http.expectOne('/api/public/cart/items/p1/v3').flush({ ...testCart, items: [] });
    expect(cart.contains('p1')).toBe(false);
  });
  function signIn() {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'customer-access', refreshToken: 'r', expiresIn: 900 });
  }
  it('merges the anonymous bag on login, shares recovery requests and attaches customer authorization', () => {
    create(); signIn();
    const first = vi.fn(); const second = vi.fn();
    cart.load().subscribe(first); cart.load().subscribe(second);
    const recovery = http.expectOne('/api/public/cart/customer');
    expect(recovery.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    expect(recovery.request.headers.get('Authorization')).toBe('Bearer customer-access');
    recovery.flush({ token: 'account-capability', cart: testCart });
    expect(first).toHaveBeenCalledWith(testCart); expect(second).toHaveBeenCalledWith(testCart);
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBeNull();
    cart.setQuantity('p1', 'v1', 1).subscribe();
    const write = http.expectOne('/api/public/cart/items/p1/v1');
    expect(write.request.headers.get('Cart-Token')).toBe('account-capability');
    expect(write.request.headers.get('Authorization')).toBe('Bearer customer-access');
    write.flush(testCart);
  });
  it('recovers the account cart without a browser token and never persists its capability', () => {
    signIn();
    const reloaded = TestBed.runInInjectionContext(() => new CartService());
    reloaded.load().subscribe();
    const recovery = http.expectOne('/api/public/cart/customer');
    expect(recovery.request.headers.has('Cart-Token')).toBe(false);
    recovery.flush({ token: 'account-capability', cart: testCart });
    expect(reloaded.count()).toBe(2);
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBeNull();
  });
  it('drops account state on logout so the next visitor cannot read the previous bag', () => {
    signIn(); cart.load().subscribe();
    http.expectOne('/api/public/cart/customer').flush({ token: 'account-capability', cart: testCart });
    TestBed.inject(AuthService).logout().subscribe();
    http.expectOne('/api/public/store-auth/session/logout').flush(null);
    expect(cart.cart()).toBeNull();
    expect(() => cart.headers()).toThrow();
    cart.load().subscribe((value) => expect(value).toBeNull());
    http.expectNone('/api/public/cart');
    cart.add('p1', 'v1', 1).subscribe();
    http.expectOne('/api/public/cart').flush({ token: 'new-anonymous' });
    http.expectOne('/api/public/cart').flush({ ...testCart, items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush(testCart);
  });
  it('preserves the source token and reports recovery failures rather than showing an empty bag', () => {
    create(); signIn(); const failed = vi.fn();
    cart.load().subscribe({ error: failed });
    http.expectOne('/api/public/cart/customer').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(failed).toHaveBeenCalledOnce(); expect(cart.recoveryError()).not.toBe('');
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBe('opaque-cart-token');
    cart.load().subscribe();
    const retry = http.expectOne('/api/public/cart/customer');
    expect(retry.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    retry.flush({ token: 'account-capability', cart: testCart });
    expect(cart.recoveryError()).toBe('');
  });
  it('requires explicit action to discard an expired anonymous source before account recovery', () => {
    create(); signIn();
    cart.load().subscribe({ error: () => undefined });
    http.expectOne('/api/public/cart/customer').flush({}, { status: 404, statusText: 'Not Found' });
    expect(cart.canDiscardAnonymous()).toBe(true);
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBe('opaque-cart-token');
    cart.forgetExpiredAnonymous().subscribe();
    const retry = http.expectOne('/api/public/cart/customer');
    expect(retry.request.headers.has('Cart-Token')).toBe(false);
    retry.flush({ token: 'recovered', cart: testCart });
    expect(cart.canDiscardAnonymous()).toBe(false);
  });
  it('merges on authenticated registration and hides the owned bag on logout', () => {
    create();
    TestBed.inject(AuthService).registerCustomer('Customer', 'customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/register').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', expiresIn: 900 });
    cart.load().subscribe();
    const merge = http.expectOne('/api/public/cart/customer');
    expect(merge.request.headers.get('Cart-Token')).toBe('opaque-cart-token');
    merge.flush({ token: 'owned', cart: testCart });
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBeNull();
    const auth = TestBed.inject(AuthService);
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'renewed', expiresIn: 900 });
    expect(cart.count()).toBe(2);
    expect(cart.headers().get('Cart-Token')).toBe('owned');
    http.expectNone('/api/public/cart/customer');
    auth.logout().subscribe();
    http.expectOne('/api/public/store-auth/session/logout').flush(null);
    expect(cart.count()).toBe(0);
    cart.load().subscribe((value) => expect(value).toBeNull());
    http.expectNone('/api/public/cart');
  });
});
