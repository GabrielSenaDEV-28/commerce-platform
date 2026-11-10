import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouteReuseStrategy } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { LoginComponent } from './login/login.component';
import { NotFoundComponent } from './not-found.component';
import { RegisterComponent } from './register/register.component';
import { AuthService } from './auth/auth.service';
import { sessionInterceptor } from './auth/session.interceptor';
import { EditableRouteReuseStrategy } from './editable-route-reuse.strategy';
import { vi } from 'vitest';
import { DOCUMENT } from '@angular/common';
import { MerchantAdminComponent } from './merchant-admin/merchant-admin.component';

describe('login routes', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), { provide: RouteReuseStrategy, useClass: EditableRouteReuseStrategy },
        provideHttpClient(withInterceptors([sessionInterceptor])), provideHttpClientTesting()],
    });
    for (const component of [LoginComponent, MerchantAdminComponent]) {
      TestBed.overrideComponent(component, { add: { providers: [{ provide: DOCUMENT, useValue: {
        location: { hostname: 'loja.localhost', protocol: 'http:', port: '4200' }, defaultView: window,
      } }] } });
    }
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => { http.verify(); vi.restoreAllMocks(); });

  function fill(selector: string, value: string): void {
    const input = harness.routeNativeElement!.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(): void {
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    harness.detectChanges();
  }

  it('keeps the legacy login URL as an alias without account-type choices', async () => {
    await harness.navigateByUrl('/painel/login', LoginComponent);
    expect(TestBed.inject(Router).url).toBe('/entrar');
    await harness.navigateByUrl('/entrar', LoginComponent);
    expect(harness.routeNativeElement!.textContent).toContain('Entre na sua conta');
    expect((harness.routeNativeElement!.querySelector('#email') as HTMLInputElement).value).toBe('');
    expect((harness.routeNativeElement!.querySelector('#password') as HTMLInputElement).value).toBe('');
    await harness.navigateByUrl('/painel/login', LoginComponent);
    expect(harness.routeNativeElement!.textContent).not.toContain('administrador');
    expect(harness.routeNativeElement!.textContent).not.toContain('lojista');
  });

  it('validates input and displays loading and authentication errors', async () => {
    await harness.navigateByUrl('/entrar', LoginComponent);
    submit();
    expect(harness.routeNativeElement!.textContent).toContain('Informe um e-mail válido');
    http.expectNone('/api/public/store-auth/login');
    fill('#email', 'customer@example.com');
    fill('#password', 'secret');
    submit();
    expect(harness.routeNativeElement!.textContent).toContain('Entrando...');
    http.expectOne('/api/public/store-auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('E-mail ou senha inválidos');
    expect(harness.routeNativeElement!.querySelector('button')!.disabled).toBe(false);
  });

  it('identifies the owner from the server response and opens administration', async () => {
    await harness.navigateByUrl('/entrar?returnUrl=%2Fpainel%2Fperfil', LoginComponent);
    fill('#email', 'merchant@example.com');
    fill('#password', 'secret');
    submit();
    http.expectOne('/api/public/store-auth/login').flush({
      accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant',
    });
    await harness.fixture.whenStable();
    http.expectOne('/api/store/me').flush({ name: 'Loja', subdomain: 'loja' });
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/painel/perfil');
    expect(TestBed.inject(AuthService).accountKind()).toBe('merchant');
    expect(harness.routeNativeElement!.textContent).toContain('Perfil e loja');
  });

  it('cancels the pending login when leaving its route', async () => {
    await harness.navigateByUrl('/entrar', LoginComponent);
    fill('#email', 'customer@example.com');
    fill('#password', 'secret');
    submit();
    const request = http.expectOne('/api/public/store-auth/login');
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    expect(request.cancelled).toBe(true);
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Cadastre-se nesta loja');
    expect(harness.routeNativeElement!.textContent).not.toContain('Loja não encontrada');
    expect(harness.routeNativeElement!.querySelector('button')!.disabled).toBe(false);
  });

  it.each(['/desconhecido', '/painel/inexistente', '/conta/inexistente'])('renders a 404 for %s without redirecting to login', async (url) => {
    await harness.navigateByUrl(url, NotFoundComponent);
    expect(TestBed.inject(Router).url).toBe(url);
    expect(harness.routeNativeElement!.textContent).toContain('Este caminho não existe');
  });

  it('preserves a protected destination and returns there after login', async () => {
    await harness.navigateByUrl('/conta/pagamentos', LoginComponent);
    expect(TestBed.inject(Router).url).toContain('returnUrl=%2Fconta%2Fpagamentos');
    fill('#email', 'customer@example.com'); fill('#password', 'secret'); submit();
    http.expectOne('/api/public/store-auth/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900, accountKind: 'customer' });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/conta/pagamentos');
  });

  it('asks before leaving a modified form and keeps it when discard is rejected', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fill('#name', 'Nome não salvo');
    await harness.navigateByUrl('/entrar');
    expect(confirm).toHaveBeenCalled();
    expect(TestBed.inject(Router).url).toBe('/cadastro');
    expect((harness.routeNativeElement!.querySelector('#name') as HTMLInputElement).value).toBe('Nome não salvo');
    confirm.mockReturnValue(true);
    await harness.navigateByUrl('/entrar', LoginComponent);
  });

  it('uses the native unload warning for unsaved input', async () => {
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fill('#name', 'Nome não salvo');
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
  });

  it('protects unsaved changes when switching sections in a reused customer page', async () => {
    TestBed.inject(AuthService).login('customer', 'customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/customer-auth/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 });
    await harness.navigateByUrl('/conta/perfil');
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', email: 'customer@example.com', phone: '11999999999' });
    harness.detectChanges(); fill('#profile-name', 'Nome não salvo');
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await harness.navigateByUrl('/conta/enderecos');
    expect(TestBed.inject(Router).url).toBe('/conta/perfil');
    http.expectNone('/api/public/customer/addresses');
    confirm.mockReturnValue(true);
    await harness.navigateByUrl('/conta/enderecos');
    http.expectOne('/api/public/customer/addresses').flush([]);
    expect(TestBed.inject(Router).url).toBe('/conta/enderecos');
  });

  it('blocks leaving while registration is being saved', async () => {
    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fill('#name', 'Cliente'); fill('#email', 'customer@example.com');
    fill('#password', 'password1234'); fill('#confirmPassword', 'password1234'); submit();
    const request = http.expectOne('/api/public/customer-auth/register');
    await harness.navigateByUrl('/entrar');
    expect(TestBed.inject(Router).url).toBe('/cadastro');
    expect(request.cancelled).toBe(false);
    request.flush(null);
  });

  it('expires a dirty authenticated page without blocking on the discard prompt', async () => {
    const auth = TestBed.inject(AuthService);
    auth.login('customer', 'customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/customer-auth/login').flush({ accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 });
    await harness.navigateByUrl('/conta/perfil');
    http.expectOne('/api/public/customer/profile').flush({ name: 'Cliente', email: 'customer@example.com', phone: '11999999999' });
    harness.detectChanges();
    fill('#profile-name', 'Não salvo');
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    auth.expire('customer', 'access');
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(confirm).not.toHaveBeenCalled();
    expect(TestBed.inject(Router).url).toContain('/entrar?expired=1');
    expect(harness.routeNativeElement!.textContent).toContain('Sua sessão expirou');
    expect(auth.accountKind()).toBeNull();
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fill('#name', 'Novo cadastro não salvo');
    await harness.navigateByUrl('/entrar');
    expect(confirm).toHaveBeenCalled();
    expect(TestBed.inject(Router).url).toBe('/cadastro');
  });
});
