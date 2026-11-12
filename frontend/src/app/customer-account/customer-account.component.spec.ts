import { DOCUMENT, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { CustomerAccountComponent } from './customer-account.component';
import { LoginComponent } from '../login/login.component';
import { StorefrontComponent } from '../storefront/storefront.component';

describe('customer account', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let auth: AuthService;
  const profile = { name: 'Ana', email: 'ana@example.com', phone: null };
  const address = { id: 'address-1', recipient: 'Ana', postalCode: '01001000', street: 'Rua A',
    number: '10', complement: '', district: 'Centro', city: 'São Paulo', state: 'SP', country: 'BR' };

  beforeEach(async () => {
    registerLocaleData(localePt);
    TestBed.configureTestingModule({ providers: [provideRouter(routes.map((route) => route.component === StorefrontComponent ? { ...route, canMatch: undefined } : route)), provideHttpClient(), provideHttpClientTesting()] });
    TestBed.overrideComponent(LoginComponent, { add: { providers: [{ provide: DOCUMENT, useValue: {
      location: { hostname: 'loja.localhost' }, defaultView: window,
    } }] } });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    harness = await RouterTestingHarness.create();
  });
  afterEach(() => http.verify());

  function signIn(kind: 'merchant' | 'customer' = 'customer') {
    auth.login(kind, 'ana@example.com', 'password').subscribe();
    http.expectOne(kind === 'customer' ? '/api/public/customer-auth/login' : '/api/auth/login')
      .flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 });
  }
  function request(url: string) {
    const result = http.expectOne(url);
    expect(result.request.headers.get('Authorization')).toBe('Bearer access');
    return result;
  }
  function fill(selector: string, value: string) {
    const input = harness.routeNativeElement!.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('change'));
  }
  function submit() {
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    harness.detectChanges();
  }
  function text() { return harness.routeNativeElement!.textContent ?? ''; }

  it('redirects guests to login and owners to their administration without loading customer data', async () => {
    await harness.navigateByUrl('/conta/perfil', LoginComponent);
    signIn('merchant');
    await harness.navigateByUrl('/conta/perfil');
    request('/api/store/me').flush({ name: 'Loja', subdomain: 'loja' });
    expect(TestBed.inject(Router).url).toBe('/painel/perfil');
    http.expectNone('/api/public/customer/profile');
  });

  it('opens the storefront after customer login and can then open and update the profile', async () => {
    await harness.navigateByUrl('/entrar', LoginComponent);
    fill('#email', 'ana@example.com');
    fill('#password', 'password');
    submit();
    http.expectOne('/api/public/store-auth/session/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'customer', identity: 'customer-id' });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/');
    http.expectOne((req) => req.url === '/api/public/catalog/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
    await harness.navigateByUrl('/conta/perfil', CustomerAccountComponent);
    request('/api/public/customer/profile').flush(profile);
    harness.detectChanges();
    expect(text()).toContain('Dados pessoais');
    expect(harness.routeNativeElement!.querySelector('nav[aria-label="Menu da conta"] a[href="/conta/pagamentos"]')).toBeNull();
    expect(harness.routeNativeElement!.querySelector('details a[href="/conta/pagamentos"]')!.textContent).toContain('Em breve');
    fill('#profile-name', 'Ana Silva');
    fill('#profile-phone', '123');
    submit();
    http.expectNone('/api/public/customer/profile');
    expect(text()).toContain('10 ou 11 dígitos');
    fill('#profile-phone', '11987654321');
    submit();
    const save = request('/api/public/customer/profile');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ name: 'Ana Silva', phone: '11987654321' });
    save.flush({ ...profile, name: 'Ana Silva', phone: '11987654321' });
    harness.detectChanges();
    expect(text()).toContain('Dados atualizados');
    expect(harness.routeNativeElement!.querySelector('#profile-name')).toBeNull();
    expect(text()).toContain('Ana Silva');
  });

  it('creates, edits and explicitly confirms deletion of an address', async () => {
    signIn();
    await harness.navigateByUrl('/conta/enderecos', CustomerAccountComponent);
    request('/api/public/customer/addresses').flush([]);
    harness.detectChanges();
    expect(text()).toContain('Nenhum endereço cadastrado');
    (harness.routeNativeElement!.querySelector('.primary') as HTMLButtonElement).click();
    harness.detectChanges();
    submit();
    expect(harness.routeNativeElement!.querySelector('#recipient')!.nextElementSibling!.textContent).toContain('Informe o destinatário');
    expect(harness.routeNativeElement!.querySelector('#postal')!.nextElementSibling!.textContent).toContain('8 números');
    fill('#recipient', 'Ana'); fill('#postal', '01001000'); fill('#street', 'Rua A');
    fill('#number', '10'); fill('#district', 'Centro'); fill('#city', 'São Paulo'); fill('#state', 'SP');
    submit();
    const create = request('/api/public/customer/addresses');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ recipient: 'Ana', postalCode: '01001000', street: 'Rua A', number: '10',
      complement: '', district: 'Centro', city: 'São Paulo', state: 'SP' });
    create.flush(address);
    harness.detectChanges();
    (harness.routeNativeElement!.querySelector('.text-button') as HTMLButtonElement).click();
    harness.detectChanges();
    fill('#number', '20');
    submit();
    const update = request('/api/public/customer/addresses/address-1');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.number).toBe('20');
    update.flush({ ...address, number: '20' });
    harness.detectChanges();
    (harness.routeNativeElement!.querySelector('.danger') as HTMLButtonElement).click();
    harness.detectChanges();
    expect(text()).toContain('Deseja remover este endereço?');
    http.expectNone('/api/public/customer/addresses/address-1');
    (harness.routeNativeElement!.querySelector('.danger') as HTMLButtonElement).click();
    const remove = request('/api/public/customer/addresses/address-1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
    harness.detectChanges();
    expect(text()).toContain('Endereço removido');
  });

  it('displays and paginates owned orders', async () => {
    signIn();
    await harness.navigateByUrl('/conta/pedidos', CustomerAccountComponent);
    request('/api/public/customer/orders?page=0&size=10').flush({ content: [
      { id: 'order-1', status: 'PENDING', createdAt: '2026-01-01T10:00:00Z', total: 45, fulfillmentType: 'PICKUP' },
    ], page: 0, totalPages: 2 });
    harness.detectChanges();
    expect(text()).toContain('Pendente');
    expect(text()).toContain('Retirada');
    (harness.routeNativeElement!.querySelectorAll('.pagination button')[1] as HTMLButtonElement).click();
    request('/api/public/customer/orders?page=1&size=10').flush({ content: [], page: 1, totalPages: 2 });
    harness.detectChanges();
    expect(text()).toContain('Não há pedidos nesta página');
    (harness.routeNativeElement!.querySelector('.empty button') as HTMLButtonElement).click();
    request('/api/public/customer/orders?page=0&size=10').flush({ content: [], page: 0, totalPages: 0 });
    harness.detectChanges();
    expect(text()).toContain('Nenhum pedido por aqui');
  });

  it('opens the storefront instead of the login screen for a signed-in customer', async () => {
    signIn();
    await harness.navigateByUrl('/entrar', StorefrontComponent);
    http.expectOne((req) => req.url === '/api/public/catalog/products').flush({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    http.expectOne('/api/public/cart/customer').flush({ token: 'customer-cart', cart: { items: [] } });
    expect(TestBed.inject(Router).url).toBe('/');
  });

  it('reports an expired session and allows logout', async () => {
    signIn();
    await harness.navigateByUrl('/conta/perfil', CustomerAccountComponent);
    request('/api/public/customer/profile').flush({}, { status: 401, statusText: 'Unauthorized' });
    harness.detectChanges();
    expect(text()).toContain('Sua sessão expirou');
    (harness.routeNativeElement!.querySelector('.message button') as HTMLButtonElement).click();
    http.expectOne('/api/public/customer-auth/logout').flush(null);
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/entrar');
  });

  it('shows unavailable features without collecting payment or credential data', async () => {
    signIn();
    await harness.navigateByUrl('/conta/pagamentos', CustomerAccountComponent);
    expect(text()).toContain('não está disponível');
    expect(harness.routeNativeElement!.querySelector('input')).toBeNull();
    await harness.navigateByUrl('/conta/seguranca', CustomerAccountComponent);
    expect(text()).toContain('Alterar senha');
    expect(text()).toContain('Alterar e-mail');
    expect(harness.routeNativeElement!.querySelector('input')).toBeNull();
  });
  it('reuses the panel and sidebar when switching tabs without replacing content with a full skeleton', async () => {
    signIn();
    const first = await harness.navigateByUrl('/conta/perfil', CustomerAccountComponent);
    request('/api/public/customer/profile').flush(profile); harness.detectChanges();
    const panel = harness.routeNativeElement!.querySelector('.panel')!;
    const sidebar = harness.routeNativeElement!.querySelector('.sidebar');
    const second = await harness.navigateByUrl('/conta/enderecos', CustomerAccountComponent);
    harness.detectChanges();
    expect(second).toBe(first);
    expect(harness.routeNativeElement!.querySelector('.panel')).toBe(panel);
    expect(harness.routeNativeElement!.querySelector('.sidebar')).toBe(sidebar);
    expect(panel.getAttribute('aria-busy')).toBe('true');
    expect(panel.querySelector('h2')!.textContent).toBe('Meus endereços');
    expect(panel.querySelector('.loading')).toBeNull();
    expect(panel.textContent).not.toContain('Nenhum endereço cadastrado');
    request('/api/public/customer/addresses').flush([address]); harness.detectChanges();
    expect(panel.getAttribute('aria-busy')).toBe('false');
    expect(panel.textContent).toContain('Rua A');
  });
});
