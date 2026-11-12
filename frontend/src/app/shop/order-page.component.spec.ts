import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
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
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'o1' }) } } },
    ] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
  });
  afterEach(() => http.verify());
  it('fetches the authenticated owner endpoint and uses server snapshots and total', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    const get = http.expectOne('/api/public/customer/orders/o1');
    expect(get.request.headers.get('Authorization')).toBe('Bearer access');
    expect(get.request.headers.has('Cart-Token')).toBe(false);
    get.flush(order); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Pendente');
    expect(fixture.nativeElement.textContent).toContain('Blusa Azul M');
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('82,00');
    expect(fixture.nativeElement.textContent).toContain('não confirma pagamento');
    fixture.nativeElement.querySelector('button').click();
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, status: 'EXPIRED' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Expirado');
  });
  it('renders legacy delivery orders without shipping details', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, total: 72, delivery: { ...order.delivery, shipping: null } });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('não possui frete registrado');
    expect(fixture.nativeElement.querySelector('.total').textContent).toContain('72,00');
  });
  it('renders pickup snapshots and does not invent delivery data', () => {
    const fixture = TestBed.createComponent(OrderPageComponent);
    http.expectOne('/api/public/customer/orders/o1').flush({ ...order, fulfillmentType: 'PICKUP', delivery: null, pickup: { address, phone: '11987654321', instructions: 'Aguarde a loja.', cost: 0 } });
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
});
