import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShippingEstimateComponent } from './shipping-estimate.component';

describe('shipping estimates', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function setup() {
    const fixture = TestBed.createComponent(ShippingEstimateComponent);
    fixture.componentRef.setInput('productId', 'p1'); fixture.componentRef.setInput('variantId', 'v1');
    fixture.componentRef.setInput('quantity', 2); fixture.detectChanges(); return fixture;
  }
  function fill(fixture: { nativeElement: HTMLElement }, value: string) {
    const input = fixture.nativeElement.querySelector('input')!;
    input.value = value; input.dispatchEvent(new Event('input'));
  }
  function submit(fixture: { nativeElement: HTMLElement; detectChanges(): void }) {
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit')); fixture.detectChanges();
  }
  it('validates CEP locally and sends the selected variant and quantity to the backend', () => {
    const fixture = setup(); fill(fixture, '123'); submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('8 números');
    http.expectNone((req) => req.url.endsWith('/shipping-estimates'));
    fill(fixture, '01001-000'); submit(fixture);
    const request = http.expectOne('/api/public/catalog/products/p1/variants/v1/shipping-estimates');
    expect(request.request.body).toEqual({ postalCode: '01001000', quantity: 2 });
    request.flush({ quotes: [{ id: 1, name: 'PAC', price: 19.9, deliveryDays: 5 }], unavailable: [] }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('19,90');
    expect(fixture.nativeElement.textContent).toContain('5 dia(s) úteis');
    fixture.componentRef.setInput('quantity', 3); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('19,90');
  });
  it('cancels stale estimates when a variant or CEP changes', () => {
    const fixture = setup(); fill(fixture, '01001000'); submit(fixture);
    const first = http.expectOne((req) => req.url.endsWith('/shipping-estimates'));
    fixture.componentRef.setInput('variantId', 'v2'); fixture.detectChanges();
    expect(first.cancelled).toBe(true); submit(fixture);
    const second = http.expectOne('/api/public/catalog/products/p1/variants/v2/shipping-estimates');
    fill(fixture, '02002000'); expect(second.cancelled).toBe(true);
  });
  it.each([409, 503])('handles unavailable quotes without inventing freight prices (%i)', (status) => {
    const fixture = setup(); fill(fixture, '01001000'); submit(fixture);
    http.expectOne((req) => req.url.endsWith('/shipping-estimates')).flush({}, { status, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.field-error')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('R$');
  });
});
