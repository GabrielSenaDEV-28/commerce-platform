import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { MerchantOrdersComponent } from './merchant-orders.component';

describe('merchant order workspace', () => {
  let http: HttpTestingController;
  const snapshot = { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap({}) };
  const record = { id: '11111111-1111-4111-8111-111111111111', customerName: 'Cliente', customerEmail: 'a@example.test', status: 'PAID',
    createdAt: '2026-11-20T10:00:00Z', expiresAt: '2026-11-20T10:30:00Z', total: 82, fulfillmentType: 'PICKUP',
    items: [{ variantId: 'v1', name: 'Blusa', quantity: 2, unitPrice: 36 }], pickup: { address: { street: 'Rua', number: '1', city: 'São Paulo', state: 'SP' }, phone: '11999999999', cost: 0 }, delivery: null };
  beforeEach(() => {
    registerLocaleData(localePt);
    snapshot.paramMap = convertToParamMap({}); snapshot.queryParamMap = convertToParamMap({});
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { merchantAccessToken: () => 'token' } },
      { provide: ActivatedRoute, useValue: { snapshot } },
    ] });
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); vi.useRealTimers(); });
  function button(root: HTMLElement, text: string) { return [...root.querySelectorAll('button')].find(b => b.textContent?.trim() === text)!; }
  function fill(root: HTMLElement, field: string, value: string) {
    const input = root.querySelector(`[formControlName="${field}"]`) as HTMLInputElement;
    input.value = value; input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
  }
  it('defaults to the current Monday-Sunday week, 50 rows and a real table', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-20T10:00:00'));
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    const request = http.expectOne(r => r.url === '/api/orders');
    expect(request.request.params.get('startDate')).toBe('2026-11-16');
    expect(request.request.params.get('endDate')).toBe('2026-11-22');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token');
    request.flush({ content: [record], page: 0, totalPages: 2, totalElements: 51 }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('table tbody tr')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('aside')).toBeNull();
    expect(fixture.nativeElement.querySelector('.heading a[appBack]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.breadcrumbs [aria-current="page"]').textContent).toBe('Pedidos');
    expect(fixture.nativeElement.textContent).toContain('51');
    expect(fixture.nativeElement.querySelector(`a[href="/painel/pedidos/${record.id}"]`)).not.toBeNull();
  });
  it('applies dates and dropdowns immediately, debounces CPF and never stores CPF in navigation history', () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    http.expectOne(r => r.url === '/api/orders').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 }); fixture.detectChanges();
    fill(fixture.nativeElement, 'startDate', '2026-11-01');
    const obsolete = http.expectOne(r => r.url === '/api/orders');
    fill(fixture.nativeElement, 'endDate', '2026-11-20');
    expect(obsolete.cancelled).toBe(true);
    const dated = http.expectOne(r => r.url === '/api/orders');
    fill(fixture.nativeElement, 'status', 'PAID');
    expect(dated.cancelled).toBe(true);
    const statusRequest = http.expectOne(r => r.url === '/api/orders');
    expect(statusRequest.request.params.get('status')).toBe('PAID');
    fill(fixture.nativeElement, 'fulfillmentType', 'PICKUP');
    expect(statusRequest.cancelled).toBe(true);
    http.expectOne(r => r.url === '/api/orders').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    fill(fixture.nativeElement, 'cpf', '529.982.247-25');
    http.expectNone(r => r.url === '/api/orders');
    vi.advanceTimersByTime(350);
    const request = http.expectOne(r => r.url === '/api/orders');
    expect(request.request.params.get('cpf')).toBe('52998224725');
    expect(request.request.params.get('fulfillmentType')).toBe('PICKUP');
    expect(request.request.params.get('status')).toBe('PAID');
    request.flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    const navigate = vi.mocked(TestBed.inject(Router).navigate);
    expect(navigate.mock.lastCall?.[1]?.queryParams?.['cpf']).toBeUndefined();
    expect(button(fixture.nativeElement, 'Aplicar filtros')).toBeUndefined();
  });
  it('loads full details and confirms only paid pickup without trusting a client status', () => {
    snapshot.paramMap = convertToParamMap({ orderId: record.id });
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    http.expectOne(`/api/orders/${record.id}`).flush(record); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.heading .order-total strong').textContent).toContain('82,00');
    expect(fixture.nativeElement.querySelectorAll('.order-total').length).toBe(1);
    button(fixture.nativeElement, 'Confirmar entrega ao cliente').click(); fixture.detectChanges();
    http.expectNone(r => r.method === 'POST');
    button(fixture.nativeElement, 'Sim, pedido entregue').click();
    const request = http.expectOne(`/api/orders/${record.id}/complete-pickup`);
    expect(request.request.body).toEqual({});
    request.flush({ ...record, status: 'DELIVERED', deliveredAt: '2026-11-20T11:00:00Z' }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Entregue');
    expect(button(fixture.nativeElement, 'Confirmar entrega ao cliente')).toBeUndefined();
  });
  it('does not offer fulfillment confirmation for unpaid pickup or delivery', () => {
    snapshot.paramMap = convertToParamMap({ orderId: record.id });
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    http.expectOne(`/api/orders/${record.id}`).flush({ ...record, status: 'PENDING' }); fixture.detectChanges();
    expect(button(fixture.nativeElement, 'Confirmar entrega ao cliente')).toBeUndefined();
    expect(fixture.nativeElement.textContent).toContain('Não libere produtos');
  });
  it('keeps details after uncertain confirmation and requests reconciliation', () => {
    snapshot.paramMap = convertToParamMap({ orderId: record.id });
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    http.expectOne(`/api/orders/${record.id}`).flush(record); fixture.detectChanges();
    button(fixture.nativeElement, 'Confirmar entrega ao cliente').click(); fixture.detectChanges();
    button(fixture.nativeElement, 'Sim, pedido entregue').click();
    http.expectOne(`/api/orders/${record.id}/complete-pickup`).error(new ProgressEvent('error')); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Consulte novamente');
    expect(fixture.nativeElement.querySelector('.order-total')).not.toBeNull();
  });
  it('refreshes details automatically and pauses while delivery confirmation is open', () => {
    vi.useFakeTimers();
    snapshot.paramMap = convertToParamMap({ orderId: record.id });
    const fixture = TestBed.createComponent(MerchantOrdersComponent);
    http.expectOne(`/api/orders/${record.id}`).flush(record); fixture.detectChanges();
    expect(button(fixture.nativeElement, 'Atualizar pedido')).toBeUndefined();
    vi.advanceTimersByTime(20_000);
    http.expectOne(`/api/orders/${record.id}`).flush(record); fixture.detectChanges();
    button(fixture.nativeElement, 'Confirmar entrega ao cliente').click(); fixture.detectChanges();
    vi.advanceTimersByTime(20_000);
    http.expectNone(`/api/orders/${record.id}`);
  });
});
