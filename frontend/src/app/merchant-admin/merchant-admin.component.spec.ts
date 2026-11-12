import { DOCUMENT, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { BehaviorSubject } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { MerchantAdminComponent } from './merchant-admin.component';

describe('merchant administration', () => {
  let http: HttpTestingController;
  const params = new BehaviorSubject(convertToParamMap({ section: 'categorias' }));

  beforeEach(() => {
    registerLocaleData(localePt);
    params.next(convertToParamMap({ section: 'categorias' }));
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { paramMap: params, snapshot: { queryParamMap: convertToParamMap({}) } } },
    ] });
    TestBed.overrideComponent(MerchantAdminComponent, { add: { providers: [
      { provide: DOCUMENT, useValue: { location: { hostname: 'loja.localhost', protocol: 'http:', port: '4200' } } },
    ] } });
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    TestBed.inject(AuthService).login('merchant', 'a@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'merchant-token', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 });
  });
  afterEach(() => http.verify());
  function setup() {
    const fixture = TestBed.createComponent(MerchantAdminComponent);
    const store = http.expectOne('/api/store/me');
    expect(store.request.headers.get('Authorization')).toBe('Bearer merchant-token');
    return { fixture, store };
  }
  function request(path: string) {
    const req = http.expectOne((r) => r.url === path);
    expect(req.request.headers.get('Authorization')).toBe('Bearer merchant-token');
    return req;
  }
  function fill(element: HTMLElement, selector: string, value: string) {
    const input = element.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('change'));
  }

  it('blocks administration on another store hostname', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Outra', subdomain: 'outra' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Entre pelo endereço da sua loja');
    expect(fixture.nativeElement.querySelector('a[href="http://outra.localhost:4200/entrar"]')).not.toBeNull();
    http.expectNone((req) => req.url === '/api/item-types');
  });
  it('preserves the panel and sidebar while switching sections and leaves the new heading visible during loading', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [{ id: 't1', name: 'Roupas', hasColor: true, hasSize: true }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    const panel = fixture.nativeElement.querySelector('.panel');
    const sidebar = fixture.nativeElement.querySelector('aside');
    params.next(convertToParamMap({ section: 'produtos' })); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.panel')).toBe(panel);
    expect(fixture.nativeElement.querySelector('aside')).toBe(sidebar);
    expect(panel.getAttribute('aria-busy')).toBe('true');
    expect(panel.querySelector('h2')!.textContent).toBe('Produtos');
    expect(panel.textContent).not.toContain('Roupas');
    request('/api/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.panel')).toBe(panel);
    expect(panel.getAttribute('aria-busy')).toBe('false');
    http.expectNone('/api/store/me');
  });
  it('keeps current records mounted and inert during pagination instead of clearing the panel', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [{ id: 't1', name: 'Roupas', hasColor: true, hasSize: true }], page: 0, totalPages: 2, totalElements: 21 });
    fixture.detectChanges();
    const card = fixture.nativeElement.querySelector('app-record-card');
    fixture.nativeElement.querySelectorAll('.pagination button')[1].click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-record-card')).toBe(card);
    expect(card.closest('[inert]')).not.toBeNull();
    request('/api/item-types').flush({ content: [], page: 1, totalPages: 2, totalElements: 21 }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.panel').getAttribute('aria-busy')).toBe('false');
  });

  it('creates categories and lists products for the authenticated store', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();
    fill(fixture.nativeElement, '#type-name', 'Roupas');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const create = request('/api/item-types');
    expect(create.request.body).toEqual({ name: 'Roupas', hasColor: false, hasSize: false, sizeFormat: null });
    create.flush({ id: 't1', name: 'Roupas' });
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/painel', 'categorias', 't1']);
    params.next(convertToParamMap({ section: 'produtos' }));
    request('/api/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhum produto ativo encontrado');
  });

  it('creates a product with the selected type and its first variant', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    params.next(convertToParamMap({ section: 'produtos' }));
    request('/api/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.primary') as HTMLButtonElement).click();
    request('/api/variant-options').flush([]);
    request('/api/item-types').flush({ content: [{ id: 't1', name: 'Roupa', hasColor: true, hasSize: true, sizeFormat: 'LETTER', active: true }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    fill(fixture.nativeElement, '#product-type', 't1');
    fixture.detectChanges();
    fill(fixture.nativeElement, '#sku', 'SKU-1');
    fill(fixture.nativeElement, '#product-name', 'Camisa');
    fill(fixture.nativeElement, '#description', 'Algodão');
    fill(fixture.nativeElement, '#price', '99.90');
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('app-variant-options button')) as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.trim() === 'Azul')!.click();
    buttons.find((button) => button.textContent?.trim() === 'M')!.click();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const create = request('/api/products');
    expect(create.request.body).toEqual({ itemTypeId: 't1', sku: 'SKU-1', name: 'Camisa', description: 'Algodão',
      price: 99.9, variants: [{ color: 'Azul', size: 'M' }] });
    create.flush({ id: 'p1' });
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/painel', 'produtos', 'p1']);
  });

  it('renders the product card as a link with separate actions', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    params.next(convertToParamMap({ section: 'produtos' }));
    request('/api/products').flush({ content: [{ id: 'p1', name: 'Camisa', sku: 'SKU-1', price: 20, active: true }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-record-card .ui-record-main').getAttribute('href')).toBe('/painel/produtos/p1');
    expect(fixture.nativeElement.querySelector('.ui-record-actions button').textContent).toContain('Editar');
  });

  it('shows order list and explicitly labels future features', () => {
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' });
    request('/api/item-types').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    params.next(convertToParamMap({ section: 'pedidos' }));
    request('/api/orders').flush({ content: [{ id: 'p1', customerName: 'Ana', total: 25, status: 'PENDING', createdAt: '2026-01-01T10:00:00Z' }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Pendente');
    params.next(convertToParamMap({ section: 'vendas' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('ainda não estão disponíveis');
  });
  it('shows only store and shared login links in the profile', () => {
    params.next(convertToParamMap({ section: 'perfil' }));
    const { fixture, store } = setup();
    store.flush({ name: 'Loja', subdomain: 'loja' }); fixture.detectChanges();
    const links = fixture.nativeElement.querySelectorAll('.panel a') as NodeListOf<HTMLAnchorElement>;
    expect(links.length).toBe(2);
    expect([...links].map((link) => link.getAttribute('href'))).toEqual(['/', '/entrar']);
    expect(fixture.nativeElement.querySelector('.panel').textContent).not.toContain('Link de administração');
    expect(fixture.nativeElement.querySelectorAll('app-copy-link').length).toBe(2);
    expect(fixture.nativeElement.querySelector('nav[aria-label="Administração"] a[href="/painel/promocoes"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('details a[href="/painel/promocoes"]').textContent).toContain('Em breve');
  });
});
