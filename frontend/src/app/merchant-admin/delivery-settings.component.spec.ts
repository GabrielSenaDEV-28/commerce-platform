import { DOCUMENT } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../ui/toast.service';
import { DeliverySettingsComponent } from './delivery-settings.component';

describe('merchant delivery settings', () => {
  let http: HttpTestingController;
  const assign = vi.fn();
  const location = { protocol: 'https:', assign };
  const snapshot = { queryParamMap: convertToParamMap({}) };
  const settings = {
    postalCode: '01001000', street: 'Praça da Sé', number: '1', complement: null,
    district: 'Sé', city: 'São Paulo', state: 'SP', pickupEnabled: false, pickupInstructions: null,
  };
  beforeEach(() => {
    assign.mockReset();
    location.protocol = 'https:';
    snapshot.queryParamMap = convertToParamMap({});
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { merchantAccessToken: () => 'merchant-token' } },
      { provide: ActivatedRoute, useValue: { snapshot } },
    ] });
    TestBed.overrideComponent(DeliverySettingsComponent, { add: { providers: [
      { provide: DOCUMENT, useValue: { location } },
    ] } });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function setup(status = 'DISCONNECTED', saved = true) {
    const fixture = TestBed.createComponent(DeliverySettingsComponent);
    const origin = http.expectOne('/api/store/shipping-settings');
    expect(origin.request.headers.get('Authorization')).toBe('Bearer merchant-token');
    if (saved) origin.flush(settings);
    else origin.flush({}, { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/integrations/melhor-envio').flush({ status });
    fixture.detectChanges();
    return fixture;
  }
  function button(fixture: ReturnType<typeof setup>, text: string): HTMLButtonElement {
    return Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((element) => element.textContent?.trim() === text)!;
  }
  function fill(fixture: ReturnType<typeof setup>, id: string, value: string) {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
  it('loads the store origin and shows the connection without exposing credentials', () => {
    const fixture = setup('CONNECTED');
    expect(fixture.nativeElement.textContent).toContain('Conectado');
    expect(fixture.nativeElement.querySelector('#origin-street').value).toBe(settings.street);
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    expect(button(fixture, 'Conectar ao Melhor Envio')).toBeUndefined();
  });
  it('accepts an unconfigured origin but validates required fields before saving', () => {
    const fixture = setup('DISCONNECTED', false);
    button(fixture, 'Salvar origem e retirada').click(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Confira os campos obrigatórios');
    http.expectNone((request) => request.method === 'PUT');
  });
  it('normalizes the CEP, saves pickup and marks the form pristine only after confirmation', () => {
    const fixture = setup();
    fill(fixture, 'origin-postal', '01001-000');
    fill(fixture, 'origin-street', ' Nova rua ');
    const pickup = fixture.nativeElement.querySelector('input[type="checkbox"]') as HTMLInputElement;
    pickup.click();
    fill(fixture, 'pickup-instructions', ' Das 9h às 18h ');
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);
    button(fixture, 'Salvar origem e retirada').click();
    expect(fixture.componentInstance.isSaving()).toBe(true);
    const save = http.expectOne('/api/store/shipping-settings');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ ...settings, street: 'Nova rua', pickupEnabled: true, pickupInstructions: 'Das 9h às 18h' });
    save.flush(save.request.body);
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    expect(TestBed.inject(ToastService).toasts()[0].tone).toBe('success');
  });
  it('retains form changes when a save response is lost', () => {
    const fixture = setup();
    fill(fixture, 'origin-street', 'Nova rua');
    button(fixture, 'Salvar origem e retirada').click();
    http.expectOne('/api/store/shipping-settings').error(new ProgressEvent('error'));
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);
    expect(fixture.componentInstance.isSaving()).toBe(false);
    expect(TestBed.inject(ToastService).toasts()[0].text).toContain('Não foi possível confirmar');
  });
  it('does not discard unsaved fields when starting authorization', () => {
    const fixture = setup();
    fill(fixture, 'origin-street', 'Nova rua');
    button(fixture, 'Conectar ao Melhor Envio').click();
    http.expectNone('/api/integrations/melhor-envio/connect');
    expect(assign).not.toHaveBeenCalled();
  });
  it('starts OAuth with the merchant token and follows only the sandbox authorization URL', () => {
    const fixture = setup();
    button(fixture, 'Conectar ao Melhor Envio').click();
    const connect = http.expectOne('/api/integrations/melhor-envio/connect');
    expect(connect.request.method).toBe('POST');
    expect(connect.request.headers.get('Authorization')).toBe('Bearer merchant-token');
    connect.flush({ authorizationUrl: 'https://sandbox.melhorenvio.com.br/oauth/authorize?state=opaque' });
    expect(assign).toHaveBeenCalledWith('https://sandbox.melhorenvio.com.br/oauth/authorize?state=opaque');
  });
  it('rejects unexpected redirect destinations and exposes configuration failures', () => {
    const fixture = setup();
    button(fixture, 'Conectar ao Melhor Envio').click();
    http.expectOne('/api/integrations/melhor-envio/connect').flush({ authorizationUrl: 'https://example.test/steal' });
    expect(assign).not.toHaveBeenCalled();
    fixture.detectChanges();
    button(fixture, 'Conectar ao Melhor Envio').click();
    http.expectOne('/api/integrations/melhor-envio/connect').flush({ code: 'MELHOR_ENVIO_CONFIGURATION' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Confira o .env');
  });
  it('requires confirmation before disconnecting and preserves status on failure', () => {
    const fixture = setup('CONNECTED');
    button(fixture, 'Desconectar').click(); fixture.detectChanges();
    http.expectNone((request) => request.method === 'DELETE');
    button(fixture, 'Confirmar desconexão').click();
    http.expectOne('/api/integrations/melhor-envio').flush({}, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Conectado');
    expect(fixture.nativeElement.textContent).toContain('Não foi possível confirmar a desconexão');
    button(fixture, 'Confirmar desconexão').click();
    http.expectOne('/api/integrations/melhor-envio').flush(null);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não conectado');
  });
  it('blocks editing after a load outage rather than treating it as missing configuration', () => {
    const fixture = TestBed.createComponent(DeliverySettingsComponent);
    http.expectOne('/api/store/shipping-settings').flush({}, { status: 502, statusText: 'Bad Gateway' });
    http.expectOne('/api/integrations/melhor-envio').flush({}, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('fieldset').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Estado não confirmado');
    expect(button(fixture, 'Conectar ao Melhor Envio')).toBeUndefined();
  });
  it('does not treat a callback query parameter as proof of connection', () => {
    snapshot.queryParamMap = convertToParamMap({ melhorEnvio: 'connected' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = setup('REAUTHORIZE');
    expect(fixture.nativeElement.textContent).toContain('Nova autorização necessária');
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { melhorEnvio: null }, replaceUrl: true }));
  });
  it('requires HTTPS before allowing authorization', () => {
    location.protocol = 'http:';
    const fixture = setup();
    expect(button(fixture, 'Conectar ao Melhor Envio').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('endereço HTTPS');
  });
});
