import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CartPageComponent } from './cart-page.component';
import { CartService } from './cart.service';

describe('cart review', () => {
  let http: HttpTestingController;
  const item = { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 35, quantity: 2, stockQuantity: 8, available: true };
  const cart = { expiresAt: '2026-12-01T00:00:00Z', items: [item] };
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
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
  it('reconciles real totals, updates quantity, calculates cart freight and removes items', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('70,00');
    fixture.nativeElement.querySelector('[aria-label="Aumentar quantidade de Blusa"]').click();
    const update = http.expectOne('/api/public/cart/items/p1/v1');
    expect(update.request.body).toEqual({ quantity: 3 });
    update.flush({ ...cart, items: [{ ...item, quantity: 3 }] }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('105,00');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Quantidade atualizada');
    const cep = fixture.nativeElement.querySelector('#destination-cep') as HTMLInputElement;
    cep.value = '01001000'; cep.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const quote = http.expectOne('/api/public/cart/shipping-estimates');
    expect(quote.request.headers.get('Cart-Token')).toBe('opaque');
    quote.flush({ quotes: [], unavailable: [] }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhuma opção de entrega');
    const remove = [...fixture.nativeElement.querySelectorAll('button')].find((button: HTMLButtonElement) => button.textContent === 'Remover') as HTMLButtonElement;
    remove.click(); http.expectOne('/api/public/cart/items/p1/v1').flush({ ...cart, items: [] }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Sua sacola está esperando');
    expect(fixture.nativeElement.textContent).toContain('Item removido da sacola');
  });
});
