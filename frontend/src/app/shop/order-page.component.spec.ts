import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { OrderPageComponent } from './order-page.component';

describe('owned order details', () => {
  let http: HttpTestingController;
  const address = { recipient: 'Cliente', street: 'Rua', number: '1', district: 'Centro', city: 'São Paulo', state: 'SP', postalCode: '01001000' };
  const order = {
    id: 'o1', reservationId: 'r1', customerName: 'Cliente', customerEmail: 'customer@example.com',
    status: 'PENDING', createdAt: '2026-01-01T10:00:00Z', expiresAt: '2026-01-01T10:20:00Z', total: 82,
    fulfillmentType: 'DELIVERY', items: [{ productId: 'p1', variantId: 'v1', name: 'Blusa', color: 'Azul', size: 'M', unitPrice: 36, quantity: 2 }],
    delivery: { phone: '11987654321', address, shipping: { serviceId: 1, name: 'PAC', price: 10, deliveryDays: 4 } }, pickup: null,
  };
  const pixUrl = '/api/public/customer/orders/o1/pix';
  const disabled = { enabled: false, status: null, expiresAt: order.expiresAt };
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'o1' }) } } },
    ] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); vi.useRealTimers(); });
  it('reconciles payment at the deadline before showing cancellation and returns home only after acknowledgment', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:19:59Z'));
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true, value: vi.fn(function(this: HTMLDialogElement) { this.open = true; }),
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush(order);
    http.expectOne(pixUrl).flush(disabled);
    fixture.detectChanges();
    vi.advanceTimersByTime(1001);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, status: 'CANCELLED' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    http.expectOne(pixUrl).flush({ ...disabled, enabled: true, status: 'EXPIRED' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    fixture.nativeElement.querySelector('dialog button').click();
    expect(navigate).toHaveBeenCalledWith('/');
  });
  it.each(['PAID', 'DELIVERED', 'PAYMENT_REVIEW'])('never shows the expiry dialog for %s', (status) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:19:59Z'));
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, status });
    http.expectOne(pixUrl).flush({ ...disabled, enabled: true, status: status === 'PAYMENT_REVIEW' ? 'REVIEW' : 'PAID' });
    fixture.detectChanges();
    vi.advanceTimersByTime(2000);
    fixture.detectChanges();
    http.expectNone('/api/public/customer/orders/o1');
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    if (status === 'DELIVERED') expect(fixture.nativeElement.textContent).toContain('Entregue');
  });
  it('fetches the authenticated owner endpoint and uses server snapshots and total', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    const get = http.expectOne('/api/public/customer/orders/o1');
    expect(get.request.headers.get('Authorization')).toBe('Bearer access');
    expect(get.request.headers.has('Cart-Token')).toBe(false);
    get.flush(order); http.expectOne(pixUrl).flush(disabled); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Pendente');
    expect(fixture.nativeElement.textContent).toContain('Blusa Azul M');
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('82,00');
    expect(fixture.nativeElement.textContent).toContain('não confirma pagamento');
    http.expectNone('/api/public/customer/orders/o1');
  });
  it('refreshes order and payment automatically without a manual update button', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush(order);
    http.expectOne(pixUrl).flush(disabled); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Atualizar estado do pedido');
    vi.advanceTimersByTime(20_000);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, status: 'EXPIRED' });
    http.expectOne(pixUrl).flush(disabled);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Expirado');
  });
  it('renders legacy delivery orders without shipping details', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, total: 72, delivery: { ...order.delivery, shipping: null } });
    http.expectOne(pixUrl).flush(disabled);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('não possui frete registrado');
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('72,00');
  });
  it('renders pickup snapshots and does not invent delivery data', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, fulfillmentType: 'PICKUP', delivery: null, pickup: { address, phone: '11987654321', instructions: 'Aguarde a loja.', cost: 0 } });
    http.expectOne(pixUrl).flush(disabled);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Retirada na loja');
    expect(fixture.nativeElement.textContent).toContain('Aguarde a loja.');
    expect(fixture.nativeElement.textContent).not.toContain('PAC');
  });
  it('does not expose cached data when the order is inaccessible', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({}, { status: 404, statusText: 'Not Found' }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.total')).toBeNull();
    expect(fixture.nativeElement.querySelector('.field-error')).not.toBeNull();
  });
  it('generates Pix only when requested and shows the replacement deadline and provider status', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const fixture = TestBed.createComponent(OrderPageComponent);
    const root: HTMLElement = fixture.nativeElement;
    http.expectOne('/api/public/customer/orders/o1').flush(order);
    http.expectOne(pixUrl).flush({ ...disabled, enabled: true });
    fixture.detectChanges();
    const click = (label: string) => [...root.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === label)!.click();
    click('Gerar Pix de teste');
    const expiry = new Date(Date.now() + 30 * 60_000).toISOString();
    http.expectOne({ url: pixUrl, method: 'POST' }).flush({
      enabled: true, status: 'PENDING', expiresAt: expiry, copyCode: 'pix-test-code', qrImage: 'dGVzdA==',
    });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#pix-code')?.value).toBe('pix-test-code');
    expect(root.querySelector('img')?.getAttribute('src')).toBe('data:image/png;base64,dGVzdA==');
    expect(root.querySelector('.pix-amount')?.textContent).toContain('82,00');
    expect(root.textContent).toContain('Modo de teste');
    expect(root.querySelector('.pix-warning')).toBeNull();
    vi.advanceTimersByTime(20_000);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, expiresAt: expiry });
    http.expectOne({ url: pixUrl, method: 'GET' }).flush({ enabled: true, status: 'PAID', expiresAt: expiry });
    fixture.detectChanges();
    expect(root.textContent).toContain('Pago');
    expect(root.textContent).toContain('Pagamento confirmado pelo provedor');
    expect(root.querySelector('#pix-code')).toBeNull();
  });
});
