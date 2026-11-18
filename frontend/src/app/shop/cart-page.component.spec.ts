import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CartPageComponent } from './cart-page.component';
import { CartService } from './cart.service';
import { ToastService } from '../ui/toast.service';

describe('cart review', () => {
  let http: HttpTestingController;
  const item = { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 35, quantity: 2, stockQuantity: 8, available: true };
  const cart = { expiresAt: '2026-12-01T00:00:00Z', items: [item] };
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  it('shows an honest empty state without creating server state', () => {
    const fixture = TestBed.createComponent(CartPageComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Sua sacola está esperando');
    http.expectNone('/api/public/cart');
  });
  function setup() {
    TestBed.inject(CartService).add('p1', 'v1', 2).subscribe();
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ ...cart, items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush(cart);
    const fixture = TestBed.createComponent(CartPageComponent);
    http.expectOne('/api/public/cart').flush(cart); fixture.detectChanges(); return fixture;
  }
  it('updates totals and the checkout URL when items are selected, without removing them', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('70,00');
    fixture.nativeElement.querySelector('[aria-label="Aumentar quantidade de Blusa"]').click();
    const update = http.expectOne('/api/public/cart/items/p1/v1');
    expect(update.request.body).toEqual({ quantity: 3 });
    update.flush({ ...cart, items: [{ ...item, quantity: 3 }] }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('105,00');
    expect(TestBed.inject(ToastService).toasts()[0].text).toContain('Quantidade atualizada');
    expect(fixture.nativeElement.querySelector('a[href="/checkout?item=v1"]')).not.toBeNull();
    fixture.nativeElement.querySelector('input[type="checkbox"]').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('0,00');
    expect(fixture.nativeElement.querySelector('.cart-summary button').disabled).toBe(true);
    http.expectNone('/api/public/cart/items/p1/v1');
    const remove = [...fixture.nativeElement.querySelectorAll('button')].find((button: HTMLButtonElement) => button.textContent === 'Remover') as HTMLButtonElement;
    remove.click(); http.expectOne('/api/public/cart/items/p1/v1').flush({ ...cart, items: [] }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Sua sacola está esperando');
    expect(TestBed.inject(ToastService).toasts()[0].text).toContain('Item removido da sacola');
  });
});
