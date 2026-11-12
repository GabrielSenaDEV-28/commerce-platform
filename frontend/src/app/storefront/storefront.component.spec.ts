import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { StorefrontComponent } from './storefront.component';
import { AuthService } from '../auth/auth.service';
import { CartService } from '../shop/cart.service';
import { Cart } from '../shop/shop.models';
import { vi } from 'vitest';
import { throwError } from 'rxjs';

describe('StorefrontComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); localStorage.clear(); });

  function setup() {
    const fixture = TestBed.createComponent(StorefrontComponent);
    fixture.detectChanges();
    if (TestBed.inject(AuthService).accountKind() === 'customer') {
      http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
      fixture.detectChanges();
    }
    return fixture;
  }
  function request(page = 0) {
    return http.expectOne((req) => req.url === '/api/public/catalog/products'
      && req.params.get('page') === String(page) && req.params.get('size') === '12');
  }

  it('links the entire product to its detail page and opens variant selection before adding', async () => {
    const fixture = setup();
    request().flush({ content: [{ id: 'p1', name: 'Blusa', description: 'Algodão', price: 20, photos: [] }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.product-destination').getAttribute('href')).toBe('/produtos/p1');
    const dialog = fixture.nativeElement.querySelector('dialog[aria-labelledby="quick-buy-title"]') as HTMLDialogElement;
    const show = vi.fn(); Object.defineProperty(dialog, 'showModal', { value: show });
    fixture.nativeElement.querySelector('.catalog-add').click(); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    expect(show).toHaveBeenCalled();
    http.expectOne((req) => req.url === '/api/public/catalog/products/p1/variants')
      .flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    http.expectNone('/api/public/cart');
  });
  it('shows only the administration account action for the store owner', () => {
    TestBed.inject(AuthService).loginStore('owner@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant', identity: 'merchant-id' });
    const fixture = setup();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    const actions = fixture.nativeElement.querySelectorAll('.header-actions > a.desktop-account');
    expect(actions.length).toBe(1);
    expect(actions[0].getAttribute('href')).toBe('/painel/perfil');
    expect(actions[0].textContent).toContain('Administração');
    expect(fixture.nativeElement.querySelector('.header-actions').textContent).not.toContain('Entrar');
  });
  it('opens customer options in the right-hand profile drawer without navigating first', () => {
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'customer', identity: 'customer-id' });
    const fixture = setup();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const drawer = root.querySelector<HTMLDialogElement>('.header-actions .mobile-drawer')!;
    const show = vi.fn(); Object.defineProperty(drawer, 'showModal', { value: show });
    root.querySelector<HTMLButtonElement>('.header-actions .menu-trigger')!.click();
    expect(show).toHaveBeenCalledOnce(); expect(drawer.classList.contains('right')).toBe(true);
    expect([...drawer.querySelectorAll('a')].map((link) => link.getAttribute('href'))).toEqual([
      '/favoritos', '/conta/perfil', '/conta/enderecos', '/conta/pedidos', '/conta/pagamentos', '/conta/seguranca',
    ]);
    expect(drawer.textContent).toContain('Sair da conta');
    expect(root.querySelector('.header-inner > app-mobile-menu a')?.textContent).toBe('Início');
    http.expectNone('/api/public/customer/profile');
  });
  it('reports logout failures inside the profile drawer and preserves the session', () => {
    const auth = TestBed.inject(AuthService);
    auth.loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'customer', identity: 'customer-id' });
    const fixture = setup();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    const drawer: HTMLElement = fixture.nativeElement.querySelector('.header-actions dialog');
    drawer.querySelector<HTMLButtonElement>('.drawer-content > button')!.click(); fixture.detectChanges();
    http.expectOne('/api/public/store-auth/session/logout').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(drawer.querySelector('[role="alert"]')?.textContent).toContain('Não foi possível encerrar a sessão');
    expect(auth.accountKind()).toBe('customer');
    expect(drawer.querySelector<HTMLButtonElement>('.drawer-content > button')!.disabled).toBe(false);
  });
  it('closes quick add only after a confirmed write, with feedback outside the modal', async () => {
    const fixture = setup();
    request().flush({ content: [{ id: 'p1', name: 'Blusa', description: '', price: 20, photos: [] }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const dialog = root.querySelector<HTMLDialogElement>('[aria-labelledby="quick-buy-title"]')!;
    Object.defineProperty(dialog, 'showModal', { value: vi.fn() });
    const close = vi.fn(); Object.defineProperty(dialog, 'close', { value: close });
    root.querySelector<HTMLButtonElement>('.catalog-add')!.click(); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    http.expectOne((req) => req.url.endsWith('/variants')).flush({
      content: [{ id: 'v1', color: null, size: null, price: 20, quantity: 3, available: true }], page: 0, totalPages: 1, totalElements: 1,
    }); fixture.detectChanges();
    dialog.querySelector('form')!.dispatchEvent(new Event('submit')); fixture.detectChanges();
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ items: [] });
    expect(close).not.toHaveBeenCalled();
    http.expectOne('/api/public/cart/items/p1/v1').flush({ items: [{ productId: 'p1', variantId: 'v1', quantity: 1 }] });
    await fixture.whenStable(); fixture.detectChanges();
    expect(close).toHaveBeenCalledOnce(); expect(TestBed.inject(CartService).busy()).toBe(false);
    expect(dialog.querySelector('app-purchase-options')).toBeNull();
    expect(root.querySelector('.catalog-feedback')?.textContent).toContain('Blusa adicionado à sacola');
  });
  it('toggles the favorite heart without opening purchase selection or calling an API', () => {
    const fixture = setup();
    request().flush({ content: [{ id: 'p1', name: 'Blusa', description: 'Algodão', price: 20, photos: [] }], page: 0, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    const heart = fixture.nativeElement.querySelector('.favorite') as HTMLButtonElement;
    expect(heart.getAttribute('aria-pressed')).toBe('false');
    heart.click(); fixture.detectChanges();
    expect(heart.getAttribute('aria-pressed')).toBe('true');
    expect(heart.classList.contains('favorited')).toBe(true);
    expect(fixture.nativeElement.querySelector('.catalog-feedback').textContent).toContain('favoritos deste navegador');
    heart.click(); fixture.detectChanges();
    expect(heart.getAttribute('aria-pressed')).toBe('false');
    http.expectNone('/api/public/cart');
    http.expectNone((req) => req.url.endsWith('/variants'));
  });
  it('requires confirmation to remove every product variant and updates the catalog button', () => {
    const fixture = setup();
    request().flush({ content: [{ id: 'p1', name: 'Blusa', description: '', price: 20, photos: [] }], page: 0, totalPages: 1, totalElements: 1 });
    const cart = TestBed.inject(CartService);
    const item = { productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', price: 20, quantity: 1, stockQuantity: 3, available: true };
    const bag: Cart = { expiresAt: '2026-12-01T00:00:00Z', items: [item, { ...item, variantId: 'v2' }] };
    cart.add('p1', 'v1', 1).subscribe();
    http.expectOne('/api/public/cart').flush({ token: 'opaque' });
    http.expectOne('/api/public/cart').flush({ ...bag, items: [] });
    http.expectOne('/api/public/cart/items/p1/v1').flush(bag);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const dialog = root.querySelector<HTMLDialogElement>('[aria-labelledby="remove-product-title"]')!;
    const show = vi.fn(); const close = vi.fn();
    Object.defineProperty(dialog, 'showModal', { value: show });
    Object.defineProperty(dialog, 'close', { value: close });
    const action = root.querySelector<HTMLButtonElement>('.catalog-add')!;
    expect(action.textContent).toBe('Retirar do carrinho'); expect(action.classList.contains('in-cart')).toBe(true);
    action.click(); fixture.detectChanges();
    expect(show).toHaveBeenCalledOnce();
    expect(dialog.textContent).toContain('Todas as cores, tamanhos e quantidades');
    http.expectNone('/api/public/cart');
    dialog.querySelector<HTMLButtonElement>('.removal-actions button:not(.primary)')!.click();
    expect(close).toHaveBeenCalledOnce(); http.expectNone('/api/public/cart');
    action.click(); fixture.detectChanges();
    dialog.querySelector<HTMLButtonElement>('.primary')!.click(); fixture.detectChanges();
    expect(action.disabled).toBe(true);
    const cancel = new Event('cancel', { cancelable: true }); dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    http.expectOne('/api/public/cart').flush(bag);
    http.expectOne('/api/public/cart/items/p1/v1').flush({ ...bag, items: [bag.items[1]] });
    http.expectOne('/api/public/cart/items/p1/v2').flush({ ...bag, items: [] }); fixture.detectChanges();
    expect(action.textContent).toBe('Adicionar ao carrinho');
    expect(root.querySelector('.catalog-feedback')?.textContent).toContain('Todas as variantes');
  });
  it('keeps the confirmation open with an explicit partial-removal warning on failure', () => {
    const fixture = setup();
    request().flush({ content: [{ id: 'p1', name: 'Blusa', description: '', price: 20, photos: [] }], page: 0, totalPages: 1, totalElements: 1 });
    const cart = TestBed.inject(CartService);
    cart.cart.set({ expiresAt: '2026-12-01T00:00:00Z', items: [{ productId: 'p1', variantId: 'v1', name: 'Blusa', color: null, size: null, price: 20, quantity: 1, stockQuantity: 3, available: true }] });
    vi.spyOn(cart, 'removeProduct').mockReturnValue(throwError(() => new Error('Connection lost')));
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const dialog = root.querySelector<HTMLDialogElement>('[aria-labelledby="remove-product-title"]')!;
    const close = vi.fn();
    Object.defineProperty(dialog, 'showModal', { value: vi.fn() });
    Object.defineProperty(dialog, 'close', { value: close });
    root.querySelector<HTMLButtonElement>('.catalog-add')!.click(); fixture.detectChanges();
    dialog.querySelector<HTMLButtonElement>('.primary')!.click(); fixture.detectChanges();
    expect(close).not.toHaveBeenCalled();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('A remoção pode ter sido parcial');
    expect(dialog.querySelector('a')?.getAttribute('href')).toBe('/carrinho');
    expect(root.querySelector('.catalog-feedback')).toBeNull();
    expect(root.querySelector('.catalog-add')?.textContent).toBe('Retirar do carrinho');
  });
  it('shows loading and empty states without inventing products', () => {
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain('Carregando produtos');
    request().flush({ content: [], page: 0, size: 12, totalPages: 0, totalElements: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Esta loja ainda não tem produtos disponíveis');
    expect(fixture.nativeElement.querySelector('.filters button').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.header-actions a[href="/carrinho"]')).not.toBeNull();
  });

  it('shows real products and paginates without treating a default price as a variant price', () => {
    const fixture = setup();
    request().flush({ content: [{ id: '1', name: 'Camiseta', description: 'Algodão', price: 99.9, photos: [] }],
      page: 0, totalPages: 2, totalElements: 13 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Camiseta');
    expect(fixture.nativeElement.textContent).toContain('99,90');
    expect(fixture.nativeElement.textContent).toContain('variantes podem ter outro valor');
    const buttons = fixture.nativeElement.querySelectorAll('.pagination button');
    expect(buttons[0].disabled).toBe(true);
    buttons[1].click();
    fixture.detectChanges();
    request(1).flush({ content: [{ id: '2', name: 'Calça', description: '', price: 10, photos: ['https://example.com/photo.jpg'] }],
      page: 1, totalPages: 2, totalElements: 13 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Calça');
    expect(fixture.nativeElement.querySelector('img').getAttribute('src')).toBe('https://example.com/photo.jpg');
    expect(fixture.nativeElement.querySelectorAll('.pagination button')[1].disabled).toBe(true);
  });

  it('offers retry on failure instead of presenting it as an empty catalog', () => {
    const fixture = setup();
    request().flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar o catálogo');
    expect(fixture.nativeElement.textContent).not.toContain('Esta loja ainda não tem produtos disponíveis');
    fixture.nativeElement.querySelector('.retry').click();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Esta loja ainda não tem produtos disponíveis');
  });

  it('allows returning to the first page when a later page becomes empty', () => {
    const fixture = setup();
    request().flush({ content: [{ id: '1', name: 'Produto', description: '', price: 10, photos: [] }],
      page: 0, totalPages: 2, totalElements: 13 });
    fixture.detectChanges();
    fixture.nativeElement.querySelectorAll('.pagination button')[1].click();
    request(1).flush({ content: [], page: 1, totalPages: 1, totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('O catálogo pode ter mudado');
    fixture.nativeElement.querySelector('.retry').click();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
  });
});
