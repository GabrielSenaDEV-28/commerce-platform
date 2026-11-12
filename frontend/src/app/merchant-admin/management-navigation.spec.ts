import { DOCUMENT, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, RouteReuseStrategy, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { EditableRouteReuseStrategy } from '../editable-route-reuse.strategy';
import { CategoryDetailsComponent } from './category-details.component';
import { MerchantAdminComponent } from './merchant-admin.component';
import { ProductDetailsComponent } from './product-details.component';

describe('management view after save', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  const category = { id: 't1', name: 'Roupas', hasColor: false, hasSize: false, sizeFormat: null, active: true };
  beforeEach(async () => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [
      provideRouter(routes), provideHttpClient(), provideHttpClientTesting(),
      { provide: RouteReuseStrategy, useClass: EditableRouteReuseStrategy },
    ] });
    for (const component of [MerchantAdminComponent, ProductDetailsComponent]) {
      TestBed.overrideComponent(component, { add: { providers: [{ provide: DOCUMENT, useValue: {
        location: { hostname: 'loja.localhost', protocol: 'http:', port: '4200' }, defaultView: window,
      } }] } });
    }
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loginStore('owner@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accessToken: 'access', refreshToken: 'refresh', expiresIn: 900, tokenType: 'Bearer', accountKind: 'merchant', identity: 'merchant-id' });
    harness = await RouterTestingHarness.create();
  });
  afterEach(() => http.verify());
  function fill(selector: string, value: string) {
    const input = harness.routeNativeElement!.querySelector<HTMLInputElement>(selector)!;
    input.value = value; input.dispatchEvent(new Event('input'));
  }

  it('opens the saved category and can edit it again from its view', async () => {
    await harness.navigateByUrl('/painel/categorias');
    http.expectOne('/api/store/me').flush({ name: 'Loja', subdomain: 'loja' });
    http.expectOne((req) => req.url === '/api/item-types').flush({ content: [], page: 0, totalPages: 0 });
    harness.detectChanges();
    harness.routeNativeElement!.querySelector<HTMLButtonElement>('.primary')!.click(); harness.detectChanges();
    fill('#type-name', 'Roupas');
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/item-types').flush(category);
    await harness.fixture.whenStable();
    http.expectOne('/api/item-types/t1').flush(category); harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/painel/categorias/t1');
    expect(harness.routeDebugElement!.componentInstance).toBeInstanceOf(CategoryDetailsComponent);
    expect(harness.routeNativeElement!.textContent).toContain('Roupas');
    await harness.navigateByUrl('/painel/categorias?editar=t1');
    http.expectOne('/api/store/me').flush({ name: 'Loja', subdomain: 'loja' });
    http.expectOne('/api/item-types/t1').flush(category); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector<HTMLInputElement>('#type-name')!.value).toBe('Roupas');
    fill('#type-name', 'Vestidos');
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/item-types/t1').flush({ ...category, name: 'Vestidos' });
    await harness.fixture.whenStable();
    http.expectOne('/api/item-types/t1').flush({ ...category, name: 'Vestidos' }); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Vestidos');
  });

  it('switches the saved variant to view mode without recreating it or fetching it again', async () => {
    const instance = await harness.navigateByUrl('/painel/produtos/p1/variantes/v1', ProductDetailsComponent);
    http.expectOne('/api/store/me').flush({ subdomain: 'loja' });
    http.expectOne('/api/products/p1').flush({ id: 'p1', itemTypeId: 't1', name: 'Camisa', price: 20, active: true });
    const variant = { id: 'v1', color: 'Azul', size: 'M', price: null, effectivePrice: 20, active: true };
    http.expectOne('/api/products/p1/variants/v1').flush(variant);
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 3 });
    http.expectOne((req) => req.url.endsWith('/movements')).flush({ content: [], page: 0, totalPages: 0 });
    harness.detectChanges(); fill('#price-v1', '32.50');
    harness.routeNativeElement!.querySelector('form:has(#price-v1)')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/products/p1/variants/v1').flush({ ...variant, price: 32.5, effectivePrice: 32.5 });
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/painel/produtos/p1/variantes/v1?visualizar=1');
    expect(harness.routeDebugElement!.componentInstance).toBe(instance);
    expect(harness.routeNativeElement!.querySelector('#price-v1')).toBeNull();
    expect(harness.routeNativeElement!.textContent).toContain('32,50');
    expect(harness.routeNativeElement!.textContent).not.toContain('Carregando produto');
    http.expectNone('/api/products/p1/variants/v1');
    const edit = [...harness.routeNativeElement!.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.includes('Editar preço'))!;
    edit.click(); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector<HTMLInputElement>('#price-v1')!.value).toBe('32.50');
  });
});
