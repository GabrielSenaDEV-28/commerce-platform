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
    { id: 'v1', color: 'Azul', colorHex: null, size: 'M', price: 32.5, quantity: 3, available: true },
    { id: 'v2', color: 'Branco', colorHex: null, size: 'P', price: 40, quantity: 0, available: false },
  ];
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  function setup(content = variants, totalPages = 1) {
    const fixture = TestBed.createComponent(PurchaseOptionsComponent);
    fixture.componentRef.setInput('product', { id: 'p1', name: 'Blusa', price: 20, description: 'Algodão', photos: [] });
    fixture.detectChanges();
    http.expectOne((req) => req.url === '/api/public/catalog/products/p1/variants')
      .flush({ content, page: 0, totalPages, totalElements: variants.length });
    fixture.detectChanges(); return fixture;
  }
  function choose(fixture: { nativeElement: HTMLElement; detectChanges(): void }) {
    fixture.nativeElement.querySelector<HTMLButtonElement>('.color-option')!.click(); fixture.detectChanges();
    fixture.nativeElement.querySelector<HTMLButtonElement>('.size-option')!.click(); fixture.detectChanges();
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
    const unavailable = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.color-option')[1];
    expect(unavailable.disabled).toBe(true);
    expect(unavailable.querySelector('.unavailable-mark')).not.toBeNull();
    unavailable.click(); fixture.detectChanges(); submit(fixture);
    expect(unavailable.getAttribute('aria-pressed')).toBe('false');
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
    const fixture = setup(); const confirmed = vi.fn();
    fixture.componentInstance.additionConfirmed.subscribe(confirmed);
    choose(fixture); submit(fixture);
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush({}, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(confirmed).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('O estoque mudou');
    expect(fixture.nativeElement.textContent).not.toContain('Produto adicionado');
  });
  it('loads every variant page before showing the complete color and size matrix', () => {
    const fixture = setup(variants, 2);
    expect(fixture.nativeElement.querySelector('.color-option')).toBeNull();
    const request = http.expectOne((req) => req.url.endsWith('/variants'));
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [{ ...variants[0], id: 'v3', color: 'Verde' }], page: 1, totalPages: 2, totalElements: 21 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.color-option').length).toBe(3);
    expect(fixture.nativeElement.querySelector('[aria-label="Verde"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Verde');
  });
  it('cross-filters existing colors and sizes and allows clearing a choice to switch combinations', () => {
    const fixture = setup([
      { ...variants[0], id: 'gp', color: 'Verde', size: 'P' },
      { ...variants[0], id: 'gm', color: 'Verde', size: 'M' },
      { ...variants[0], id: 'cg', color: 'Cinza', size: 'G' },
    ]);
    const color = (label: string) => (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(`.color-option[aria-label^="${label}"]`)!;
    const size = (label: string) => (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(`.size-option[aria-label^="${label}"]`)!;
    expect(fixture.nativeElement.querySelectorAll('.color-option').length).toBe(2);
    expect(fixture.nativeElement.querySelectorAll('.size-option').length).toBe(3);
    color('Verde').click(); fixture.detectChanges();
    expect(size('P').disabled).toBe(false); expect(size('M').disabled).toBe(false); expect(size('G').disabled).toBe(true);
    expect(size('G').querySelector('.unavailable-mark')).not.toBeNull();
    size('P').click(); fixture.detectChanges();
    expect(color('Cinza').disabled).toBe(true);
    expect(color('Cinza').querySelector('.unavailable-mark')).not.toBeNull();
    size('P').click(); fixture.detectChanges();
    expect(color('Cinza').disabled).toBe(false);
    color('Cinza').click(); fixture.detectChanges();
    expect(size('P').disabled).toBe(true); expect(size('M').disabled).toBe(true); expect(size('G').disabled).toBe(false);
    size('G').click(); fixture.detectChanges(); submit(fixture);
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    const write = http.expectOne('/api/public/cart/items/p1/cg');
    expect(write.request.body).toEqual({ quantity: 1 });
    write.flush({ items: [] });
  });
  it('uses custom swatches and shows no visible color legend or preset options', () => {
    const fixture = setup([{ ...variants[0], color: 'Musgo especial', colorHex: '#465c35' }]);
    const color = fixture.nativeElement.querySelector('.color-option') as HTMLButtonElement;
    expect(color.getAttribute('aria-label')).toBe('Musgo especial');
    expect(color.textContent?.trim()).toBe('');
    expect(color.querySelector<HTMLElement>('.swatch')!.style.backgroundColor).toBe('rgb(70, 92, 53)');
    expect(fixture.nativeElement.querySelectorAll('.color-option').length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('.size-option').length).toBe(1);
  });
  it('supports products without color or size and blocks all sold-out options', () => {
    const fixture = setup([{ ...variants[1], color: null, size: null }]);
    expect(fixture.nativeElement.querySelector('.color-option')).toBeNull();
    expect(fixture.nativeElement.querySelector('.size-option')).toBeNull();
    expect(fixture.nativeElement.querySelector('.primary').disabled).toBe(true);
    submit(fixture); http.expectNone('/api/public/cart');
  });
  it('resolves an in-stock product with no variant attributes without creating fake options', () => {
    const fixture = setup([{ ...variants[0], color: null, size: null }]);
    expect(fixture.nativeElement.querySelector('.color-option')).toBeNull();
    expect(fixture.nativeElement.querySelector('.size-option')).toBeNull();
    submit(fixture);
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush({ items: [] });
  });
  it('allows size-only products without requiring a fake color selection', () => {
    const fixture = setup([{ ...variants[0], color: null }, { ...variants[1], color: null }]);
    expect(fixture.nativeElement.querySelector('.color-option')).toBeNull();
    fixture.nativeElement.querySelector('.size-option').click(); fixture.detectChanges(); submit(fixture);
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush({ items: [] });
  });
  it('deduplicates case-insensitive attributes and preserves their display labels', () => {
    const fixture = setup([variants[0], { ...variants[0], id: 'v3', color: 'azul', size: 'G' }]);
    expect(fixture.nativeElement.querySelectorAll('.color-option').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.color-option').getAttribute('aria-label')).toBe('Azul');
    expect(fixture.nativeElement.querySelectorAll('.size-option')[0].textContent.trim()).toBe('M');
    expect(fixture.nativeElement.querySelectorAll('.size-option')[1].textContent.trim()).toBe('G');
  });
  it('cancels old product pages when switching products instead of reusing their selections', () => {
    const fixture = setup(variants, 2);
    const old = http.expectOne((req) => req.url.endsWith('/variants'));
    fixture.componentRef.setInput('product', { id: 'p2', name: 'Outro', price: 20, description: '', photos: [] });
    fixture.detectChanges();
    expect(old.cancelled).toBe(true);
    http.expectOne((req) => req.url === '/api/public/catalog/products/p2/variants')
      .flush({ content: [{ ...variants[0], color: 'Verde' }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-label="Azul"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Verde"]')).not.toBeNull();
  });
  it('does not expose a partial matrix if a later page fails and retries from the beginning', () => {
    const fixture = setup(variants, 2);
    http.expectOne((req) => req.url.endsWith('/variants')).flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.color-option')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar');
    fixture.nativeElement.querySelector('button').click();
    const retry = http.expectOne((req) => req.url.endsWith('/variants'));
    expect(retry.request.params.get('page')).toBe('0');
    retry.flush({ content: variants, page: 0, totalPages: 1, totalElements: 2 }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.color-option').length).toBe(2);
  });
});
