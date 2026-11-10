import { DOCUMENT, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ProductDetailsComponent } from './product-details.component';
import { vi } from 'vitest';

describe('product management', () => {
  let http: HttpTestingController;
  let route: { snapshot: { paramMap: ReturnType<typeof convertToParamMap>; queryParamMap: ReturnType<typeof convertToParamMap> } };
  const path = '/api/products/p1';
  beforeEach(() => {
    sessionStorage.clear();
    registerLocaleData(localePt);
    route = { snapshot: { paramMap: convertToParamMap({ id: 'p1' }), queryParamMap: convertToParamMap({}) } };
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: route },
    ] });
    TestBed.overrideComponent(ProductDetailsComponent, { add: { providers: [
      { provide: DOCUMENT, useValue: { location: { hostname: 'loja.localhost' }, defaultView: window } },
    ] } });
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    TestBed.inject(AuthService).login('merchant', 'test@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'access', refreshToken: 'refresh', expiresIn: 900, tokenType: 'Bearer' });
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  const images = [{ id: 'a', url: 'https://example.com/a.png', position: 0 }, { id: 'b', url: 'https://example.com/b.png', position: 1 }];
  function setup(photoFailure = false, photos = images.slice(0, 0)) {
    const fixture = TestBed.createComponent(ProductDetailsComponent);
    http.expectOne('/api/store/me').flush({ subdomain: 'loja' });
    http.expectOne(path).flush({ id: 'p1', itemTypeId: 'type1', name: 'Blusa summer', price: 20, active: true });
    http.expectOne('/api/variant-options').flush([]);
    if (photoFailure) http.expectOne(`${path}/photos`).flush({}, { status: 503, statusText: 'Unavailable' });
    else http.expectOne(`${path}/photos`).flush(photos);
    http.expectOne('/api/item-types/type1').flush({ hasColor: true, hasSize: true, sizeFormat: 'LETTER' });
    loadVariants();
    fixture.detectChanges();
    return fixture;
  }
  function loadVariants() {
    http.expectOne((req) => req.url === `${path}/variants`).flush({
      page: 0, totalPages: 1, content: [{ id: 'v1', color: 'Azul', size: 'M', price: null, effectivePrice: 20 }],
    });
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 3 });
  }
  function setupVariant() {
    route.snapshot.paramMap = convertToParamMap({ id: 'p1', variantId: 'v1' });
    const fixture = TestBed.createComponent(ProductDetailsComponent);
    http.expectOne('/api/store/me').flush({ subdomain: 'loja' });
    http.expectOne(path).flush({ id: 'p1', itemTypeId: 'type1', name: 'Blusa summer', price: 20, active: true });
    loadVariant();
    fixture.detectChanges();
    return fixture;
  }
  function loadVariant() {
    http.expectOne(`${path}/variants/v1`).flush({ id: 'v1', color: 'Azul', size: 'M', price: null, effectivePrice: 20, active: true });
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 3 });
    loadHistory();
  }
  function loadHistory() {
    http.expectOne((req) => req.url === '/api/inventory/products/p1/variants/v1/movements' && req.method === 'GET')
      .flush({ content: [], page: 0, totalPages: 0 });
  }
  function fill(fixture: { nativeElement: HTMLElement }, selector: string, value: string) {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value; input.dispatchEvent(new Event('input'));
  }
  it('shows a read-only variant row with a link to its management page', () => {
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain('Blusa summer Azul');
    expect(fixture.nativeElement.querySelector('app-record-card .ui-record-main').getAttribute('href')).toBe('/painel/produtos/p1/variantes/v1');
    expect(fixture.nativeElement.querySelector('#price-v1')).toBeNull();
    expect(fixture.nativeElement.querySelector('#quantity-v1')).toBeNull();
  });
  it('changes the price on the variant management page', () => {
    const fixture = setupVariant();
    const price = fixture.nativeElement.querySelector('#price-v1') as HTMLInputElement;
    price.value = '32.50'; price.dispatchEvent(new Event('input'));
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Salvar preço')) as HTMLButtonElement;
    button.click();
    const request = http.expectOne(`${path}/variants/v1`);
    expect(request.request.body).toEqual({ price: 32.5 });
    request.flush({ id: 'v1', color: 'Azul', size: 'M', price: 32.5, effectivePrice: 32.5 });
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#price-v1')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Editar preço');
    http.expectNone(`${path}/variants/v1`);
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { visualizar: '1' } }));
  });
  it('validates and saves shipping measurements for the selected variant', () => {
    const fixture = setupVariant();
    const form = fixture.nativeElement.querySelector('form:has(#weight)') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Informe um peso positivo');
    for (const [selector, value] of [['#weight', '0.5'], ['#width', '20'], ['#height', '10'], ['#length', '30']]) {
      const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
      input.value = value; input.dispatchEvent(new Event('input'));
    }
    form.dispatchEvent(new Event('submit'));
    const request = http.expectOne(`${path}/variants/v1/shipping`);
    expect(request.request.body).toEqual({ weightKg: 0.5, widthCm: 20, heightCm: 10, lengthCm: 30 });
    request.flush({});
  });
  it('retries an uncertain stock entry with the same idempotency key', () => {
    const fixture = setupVariant();
    fill(fixture, '#quantity-v1', '5'); fill(fixture, '#reason-v1', 'Recebimento');
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Adicionar estoque')) as HTMLButtonElement;
    button.click();
    const first = http.expectOne('/api/inventory/products/p1/variants/v1/movements');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    expect(first.request.body).toEqual({ type: 'ENTRY', quantity: 5, reason: 'Recebimento' });
    first.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const retry = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('mesma tentativa')) as HTMLButtonElement;
    retry.click();
    const second = http.expectOne('/api/inventory/products/p1/variants/v1/movements');
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush({});
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 8 });
    loadHistory();
    expect(sessionStorage.getItem('inventory-pending:p1')).toBeNull();
  });
  it('recovers a pending stock entry when opening the variant page again', () => {
    sessionStorage.setItem('inventory-pending:p1', JSON.stringify({
      key: '17b608d5-28a3-4f4b-8c8a-0c12ade3d120', product: 'p1', variant: 'v1', quantity: 5, reason: 'Recebimento',
    }));
    const fixture = setupVariant();
    const retry = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('mesma tentativa')) as HTMLButtonElement;
    retry.click();
    const request = http.expectOne('/api/inventory/products/p1/variants/v1/movements');
    expect(request.request.headers.get('Idempotency-Key')).toBe('17b608d5-28a3-4f4b-8c8a-0c12ade3d120');
    request.flush({});
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 8 });
    loadHistory();
  });
  it('uploads photos as multipart data', () => {
    const fixture = setup();
    const input = fixture.nativeElement.querySelector('#photo') as HTMLInputElement;
    const file = new File(['image'], 'photo.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [file] });
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Enviar imagem')) as HTMLButtonElement;
    button.click();
    const request = http.expectOne(`${path}/photos`);
    expect((request.request.body as FormData).get('file')).toBe(file);
    request.flush({ id: 'photo1', url: 'https://example.com/image.png', position: 0 });
  });
  it('keeps variant management available when photos fail to load', () => {
    const fixture = setup(true);
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar as fotos');
    expect(fixture.nativeElement.textContent).toContain('Blusa summer Azul');
  });
  it('deactivates a variant and refreshes the list', () => {
    const fixture = setup();
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Desativar variante')) as HTMLButtonElement;
    button.click();
    const request = http.expectOne(`${path}/variants/v1`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    loadVariants();
  });
  it('displays and paginates stock history with local error recovery', () => {
    const fixture = setupVariant();
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Atualizar histórico')) as HTMLButtonElement;
    button.click();
    const first = http.expectOne((req) => req.url.endsWith('/movements'));
    expect(first.request.params.get('size')).toBe('10');
    first.flush({ content: [{ id: 'm1', type: 'ENTRY', occurredAt: '2026-11-09T12:00:00Z', beforeQuantity: 3, afterQuantity: 8, reason: 'Recebimento' }], page: 0, totalPages: 2 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Entrada · 5 unidade(s)');
    expect(fixture.nativeElement.textContent).toContain('Saldo: 3 → 8');
    const next = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Próxima')) as HTMLButtonElement;
    next.click();
    const second = http.expectOne((req) => req.url.endsWith('/movements'));
    expect(second.request.params.get('page')).toBe('1');
    second.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.stock-history')?.textContent).toContain('Não foi possível carregar o histórico');
  });
  it('preserves unsaved shipping measurements when recording stock', () => {
    const fixture = setupVariant();
    fill(fixture, '#width', '77.25');
    fill(fixture, '#quantity-v1', '5'); fill(fixture, '#reason-v1', 'Recebimento');
    const form = fixture.nativeElement.querySelector('form:has(#quantity-v1)') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    http.expectOne((req) => req.url.endsWith('/movements') && req.method === 'POST').flush({});
    http.expectOne('/api/inventory/products/p1/variants/v1').flush({ quantity: 8 });
    loadHistory();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('#width') as HTMLInputElement).value).toBe('77.25');
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);
    http.expectNone(`${path}/variants/v1`);
  });
  it('previews a photo without uploading and revokes its object URL on discard', () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview');
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const fixture = setup();
    const input = fixture.nativeElement.querySelector('#photo') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [new File(['image'], 'photo.png', { type: 'image/png' })] });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(create).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.photo-preview img').getAttribute('src')).toBe('blob:preview');
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);
    http.expectNone((req) => req.method === 'POST');
    (fixture.nativeElement.querySelector('.photo-preview button') as HTMLButtonElement).click();
    expect(revoke).toHaveBeenCalledWith('blob:preview');
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    vi.restoreAllMocks();
  });
  it('sends the complete photo order when choosing a cover', () => {
    const fixture = setup(false, images);
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Usar como principal')) as HTMLButtonElement;
    button.click();
    const request = http.expectOne(`${path}/photos/order`);
    expect(request.request.body).toEqual({ photoIds: ['b', 'a'] });
    request.flush([images[1], images[0]]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.photo-gallery img').getAttribute('src')).toBe(images[1].url);
  });
  it('requires confirmation before removing a photo', () => {
    const fixture = setup(false, images);
    const remove = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Remover foto')) as HTMLButtonElement;
    remove.click(); fixture.detectChanges();
    http.expectNone((req) => req.method === 'DELETE');
    const confirm = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Confirmar remoção')) as HTMLButtonElement;
    confirm.click();
    http.expectOne(`${path}/photos/a`).flush(null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.photo-gallery img').length).toBe(1);
  });
  it('requires refreshing the gallery after an uncertain deletion', () => {
    const fixture = setup(false, images);
    ([...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Remover foto')) as HTMLButtonElement).click();
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Confirmar remoção')) as HTMLButtonElement).click();
    http.expectOne(`${path}/photos/a`).flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.photo-upload .primary') as HTMLButtonElement).disabled).toBe(true);
    ([...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.includes('Atualizar galeria')) as HTMLButtonElement).click();
    http.expectOne(`${path}/photos`).flush([images[1]]);
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.photo-upload .primary') as HTMLButtonElement).disabled).toBe(false);
  });
  it('finishes loading an empty variant page', () => {
    const fixture = setup();
    const inactive = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) => item.textContent?.trim() === 'Inativas') as HTMLButtonElement;
    inactive.click();
    http.expectOne((req) => req.url === `${path}/variants`).flush({ content: [], page: 0, totalPages: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhuma variante inativa');
    expect(fixture.nativeElement.textContent).not.toContain('Carregando produto e estoque');
  });
});
