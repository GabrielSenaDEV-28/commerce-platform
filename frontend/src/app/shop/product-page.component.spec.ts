import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, RouteReuseStrategy } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { EditableRouteReuseStrategy } from '../editable-route-reuse.strategy';
import { ProductPageComponent } from './product-page.component';

describe('public product page', () => {
  let http: HttpTestingController;
  const route = { snapshot: { paramMap: convertToParamMap({ id: 'p1' }) } };
  const product = { id: 'p1', name: 'Blusa summer', description: 'Algodão\nDetalhes do tecido', price: 20,
    photos: ['https://example.com/one.jpg', 'https://example.com/two.jpg'] };
  beforeEach(() => {
    route.snapshot.paramMap = convertToParamMap({ id: 'p1' });
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [
      provideRouter(routes), provideHttpClient(), provideHttpClientTesting(),
      { provide: RouteReuseStrategy, useClass: EditableRouteReuseStrategy },
      { provide: ActivatedRoute, useValue: route },
    ] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  it('shows real images with gallery controls, product details and explicit payment limitations', () => {
    const fixture = TestBed.createComponent(ProductPageComponent);
    http.expectOne('/api/public/catalog/products/p1').flush(product); fixture.detectChanges();
    http.expectOne((req) => req.url.endsWith('/variants')).flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Blusa summer');
    expect(fixture.nativeElement.querySelector('.main-photo img').getAttribute('src')).toBe(product.photos[0]);
    fixture.nativeElement.querySelector('[aria-label="Próxima imagem"]').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.main-photo img').getAttribute('src')).toBe(product.photos[1]);
    fixture.nativeElement.querySelector('[aria-label="Próxima imagem"]').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.main-photo img').getAttribute('src')).toBe(product.photos[0]);
    expect(fixture.nativeElement.querySelector('.description').textContent).toContain('Detalhes do tecido');
    expect(fixture.nativeElement.querySelector('.description app-copy-link button.icon-action')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.product-summary .description app-copy-link')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.payment-info').textContent).toContain('ainda não estão disponíveis');
    expect(fixture.nativeElement.querySelector('del')).toBeNull();
  });
  it('does not present missing products as purchasable', () => {
    route.snapshot.paramMap = convertToParamMap({ id: 'missing' });
    const fixture = TestBed.createComponent(ProductPageComponent);
    http.expectOne('/api/public/catalog/products/missing').flush({}, { status: 404, statusText: 'Not Found' }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Este produto não está disponível nesta loja');
    expect(fixture.nativeElement.querySelector('app-purchase-options')).toBeNull();
  });
  it('recreates the product view when navigating between product URLs', async () => {
    const harness = await RouterTestingHarness.create();
    const first = await harness.navigateByUrl('/produtos/p1', ProductPageComponent);
    http.expectOne('/api/public/catalog/products/p1').flush(product); harness.detectChanges();
    http.expectOne((req) => req.url.endsWith('/variants')).flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    const second = await harness.navigateByUrl('/produtos/p2', ProductPageComponent);
    expect(second).not.toBe(first);
    http.expectOne('/api/public/catalog/products/p2').flush({ ...product, id: 'p2', name: 'Vestido', photos: [] }); harness.detectChanges();
    http.expectOne((req) => req.url.endsWith('/variants')).flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Vestido');
    expect(harness.routeNativeElement!.textContent).toContain('ainda não tem fotos');
    expect(harness.routeNativeElement!.textContent).not.toContain('Blusa summer');
  });
  it('opens the bag only after a direct purchase addition is confirmed and the write guard is released', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/produtos/p1', ProductPageComponent);
    http.expectOne('/api/public/catalog/products/p1').flush(product); harness.detectChanges();
    http.expectOne((req) => req.url.endsWith('/variants')).flush({
      content: [{ id: 'v1', color: 'Azul', size: 'M', price: 32.5, quantity: 3, available: true }], page: 0, totalPages: 1, totalElements: 1,
    }); harness.detectChanges();
    const buy = [...harness.routeNativeElement!.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent === 'Comprar agora')!;
    buy.click();
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    const cart = { expiresAt: '2026-12-01T00:00:00Z', items: [{ productId: 'p1', variantId: 'v1', name: product.name, color: 'Azul', size: 'M', price: 32.5, quantity: 1, stockQuantity: 3, available: true }] };
    http.expectOne('/api/public/cart/items/p1/v1').flush(cart);
    await harness.fixture.whenStable();
    http.expectOne('/api/public/cart').flush(cart); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Minha sacola');
    expect(harness.routeNativeElement!.textContent).toContain('32,50');
  });
});
