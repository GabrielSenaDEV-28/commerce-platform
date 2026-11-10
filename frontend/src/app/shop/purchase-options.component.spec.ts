import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { PurchaseOptionsComponent } from './purchase-options.component';
import { PublicVariant } from './shop.models';

describe('product purchase selection', () => {
  let http: HttpTestingController;
  const variants: PublicVariant[] = [
    { id: 'v1', color: 'Azul', size: 'M', price: 32.5, quantity: 3, available: true },
    { id: 'v2', color: 'Branco', size: 'P', price: 40, quantity: 0, available: false },
  ];
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function setup(content = variants, totalPages = 1) {
    const fixture = TestBed.createComponent(PurchaseOptionsComponent);
    fixture.componentRef.setInput('product', { id: 'p1', name: 'Blusa', price: 20, description: 'Algodão', photos: [] });
    fixture.detectChanges();
    http.expectOne((req) => req.url === '/api/public/catalog/products/p1/variants')
      .flush({ content, page: 0, totalPages, totalElements: variants.length });
    fixture.detectChanges(); return fixture;
  }
  function choose(fixture: { nativeElement: HTMLElement; detectChanges(): void }) {
    fixture.nativeElement.querySelector<HTMLButtonElement>('.variant-choice')!.click(); fixture.detectChanges();
  }
  function submit(fixture: { nativeElement: HTMLElement; detectChanges(): void }) {
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit')); fixture.detectChanges();
  }
  it('requires a variant, shows its effective price and validates stock quantity locally', () => {
    const fixture = setup(); submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('Selecione uma combinação disponível');
    http.expectNone('/api/public/cart');
    choose(fixture);
    expect(fixture.nativeElement.querySelector('.price').textContent).toContain('32,50');
    const quantity = fixture.nativeElement.querySelector('#purchase-quantity') as HTMLInputElement;
    quantity.value = '4'; quantity.dispatchEvent(new Event('input')); submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('dentro do estoque');
    http.expectNone('/api/public/cart');
    quantity.value = '1.5'; quantity.dispatchEvent(new Event('input')); submit(fixture);
    http.expectNone('/api/public/cart');
  });
  it('does not sell sold-out variants', () => {
    const fixture = setup();
    fixture.nativeElement.querySelectorAll('.variant-choice')[1].click(); fixture.detectChanges(); submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('Esta combinação está esgotada');
    expect(fixture.nativeElement.querySelector('.primary').disabled).toBe(true);
    http.expectNone('/api/public/cart');
  });
  it('uses the real cart API and opens its review for direct purchase only after confirmation', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = setup(); choose(fixture);
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Comprar agora')) as HTMLButtonElement;
    button.click();
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [], expiresAt: '2026-12-01T00:00:00Z' });
    const write = http.expectOne('/api/public/cart/items/p1/v1');
    expect(write.request.body).toEqual({ quantity: 1 });
    expect(navigate).not.toHaveBeenCalled();
    write.flush({ items: [], expiresAt: '2026-12-01T00:00:00Z' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/carrinho');
  });
  it('shows conflicts alongside the purchase operation without claiming success', () => {
    const fixture = setup(); choose(fixture); submit(fixture);
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush({}, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('O estoque mudou');
    expect(fixture.nativeElement.textContent).not.toContain('Produto adicionado');
  });
  it('loads the next variant page rather than hiding combinations beyond the first page', () => {
    const fixture = setup(variants, 2);
    const next = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent === 'Próxima') as HTMLButtonElement;
    next.click();
    const request = http.expectOne((req) => req.url.endsWith('/variants'));
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [{ ...variants[0], id: 'v3', color: 'Verde' }], page: 1, totalPages: 2, totalElements: 21 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Verde');
  });
});
