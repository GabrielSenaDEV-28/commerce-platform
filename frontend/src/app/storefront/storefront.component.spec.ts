import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { StorefrontComponent } from './storefront.component';
import { AuthService } from '../auth/auth.service';
import { vi } from 'vitest';

describe('StorefrontComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function setup() {
    const fixture = TestBed.createComponent(StorefrontComponent);
    fixture.detectChanges();
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
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
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
    http.expectOne('/api/public/store-auth/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant' });
    const fixture = setup();
    request().flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    const actions = fixture.nativeElement.querySelectorAll('.header-actions a:not([href="/carrinho"])');
    expect(actions.length).toBe(1);
    expect(actions[0].getAttribute('href')).toBe('/painel/perfil');
    expect(actions[0].textContent).toContain('Administração');
    expect(fixture.nativeElement.querySelector('.header-actions').textContent).not.toContain('Entrar');
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
