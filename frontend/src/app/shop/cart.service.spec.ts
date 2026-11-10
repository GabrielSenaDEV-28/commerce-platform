import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CartService } from './cart.service';
import { Cart } from './shop.models';
import { vi } from 'vitest';

const testCart: Cart = { expiresAt: '2026-12-01T00:00:00Z', items: [
  { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 35, quantity: 2, stockQuantity: 8, available: true },
] };

describe('shopping cart', () => {
  let http: HttpTestingController;
  let cart: CartService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController); cart = TestBed.inject(CartService);
  });
  afterEach(() => http.verify());
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
});
