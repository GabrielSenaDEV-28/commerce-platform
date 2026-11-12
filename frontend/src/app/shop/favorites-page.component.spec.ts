import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FavoritesPageComponent } from './favorites-page.component';
import { FavoritesService } from './favorites.service';

describe('favorites page', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    localStorage.clear(); registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); localStorage.clear(); });
  it('shows a real empty state without requesting invented products', () => {
    const fixture = TestBed.createComponent(FavoritesPageComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Seus favoritos começam');
    http.expectNone((req) => req.url.startsWith('/api/'));
  });
  it('fetches current product details, distinguishes unavailable products from failures and removes locally', () => {
    const favorites = TestBed.inject(FavoritesService);
    for (const id of ['p1', 'p2', 'p3']) favorites.toggle(id);
    const fixture = TestBed.createComponent(FavoritesPageComponent); fixture.detectChanges();
    http.expectOne('/api/public/catalog/products/p1').flush({ id: 'p1', name: 'Blusa', description: 'Algodão', price: 20, photos: [] });
    http.expectOne('/api/public/catalog/products/p2').flush({}, { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/public/catalog/products/p3').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Blusa');
    expect(root.textContent).toContain('Produto não disponível nesta loja');
    expect(root.textContent).toContain('Não foi possível consultar este favorito');
    expect(favorites.ids()).toEqual(['p1', 'p2', 'p3']);
    root.querySelector<HTMLButtonElement>('[aria-label="Remover Blusa dos favoritos"]')!.click(); fixture.detectChanges();
    expect(favorites.has('p1')).toBe(false); expect(root.querySelector('a[href="/produtos/p1"]')).toBeNull();
    http.expectNone('/api/public/catalog/products/p2');
    const retry = [...root.querySelectorAll<HTMLButtonElement>('button')].filter((button) => button.textContent === 'Consultar novamente')[1];
    retry.click();
    http.expectOne('/api/public/catalog/products/p3').flush({ id: 'p3', name: 'Calça', description: '', price: 30, photos: [] });
    fixture.detectChanges(); expect(root.textContent).toContain('Calça');
  });
});
