import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { RegisterComponent } from './register.component';
import { StoreNavigation } from '../auth/store-navigation.service';
import { vi } from 'vitest';

describe('registration', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  const open = vi.fn();

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    open.mockReset();
    TestBed.overrideProvider(StoreNavigation, { useFactory: () => ({ address: (subdomain: string) => `http://${subdomain}.localhost:4200`, open }) });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });
  afterEach(() => http.verify());

  function fill(selector: string, value: string): void {
    const input = harness.routeNativeElement!.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
  function submit(): void {
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit'));
    harness.detectChanges();
  }
  function fillAccount(): void {
    fill('#name', 'Gabriel');
    fill('#email', 'user@example.com');
    fill('#password', 'password1234');
    fill('#confirmPassword', 'password1234');
  }

  it('creates the store and redirects to its login without sharing tokens', async () => {
    await harness.navigateByUrl('/painel/cadastro', RegisterComponent);
    fillAccount();
    fill('#storeName', 'Minha Loja');
    fill('#subdomain', 'minha-loja');
    submit();
    const request = http.expectOne('/api/merchants');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      merchantName: 'Gabriel', storeName: 'Minha Loja', subdomain: 'minha-loja',
      email: 'user@example.com', password: 'password1234',
    });
    request.flush({ subdomain: 'minha-loja' }, { status: 201, statusText: 'Created' });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Cadastro concluído');
    expect(open).toHaveBeenCalledWith('http://minha-loja.localhost:4200/entrar');
    const storeLink = [...harness.routeNativeElement!.querySelectorAll('a')]
      .find((link) => link.textContent?.includes('/cadastro'));
    expect(storeLink).toBeDefined();
    expect(new URL(storeLink!.href).hostname).toBe('minha-loja.localhost');
    expect(new URL(storeLink!.href).pathname).toBe('/cadastro');
    expect(new URL(storeLink!.href).port).toBe('4200');
  });

  it('registers a customer using the store-scoped endpoint', async () => {
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fillAccount();
    submit();
    const request = http.expectOne('/api/public/customer-auth/register');
    expect(request.request.body).toEqual({ name: 'Gabriel', email: 'user@example.com', password: 'password1234' });
    request.flush({}, { status: 201, statusText: 'Created' });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Cadastro concluído');
    expect(harness.routeNativeElement!.querySelector('a[href="/entrar"]')).not.toBeNull();
  });

  it('rejects invalid subdomain and mismatched or short passwords', async () => {
    await harness.navigateByUrl('/painel/cadastro', RegisterComponent);
    fillAccount();
    fill('#storeName', 'Loja');
    fill('#subdomain', '-invalid');
    fill('#password', 'short');
    submit();
    expect(harness.routeNativeElement!.textContent).toContain('As senhas devem ser iguais');
    http.expectNone('/api/merchants');
    fill('#subdomain', 'valid');
    fill('#confirmPassword', 'short');
    submit();
    http.expectNone('/api/merchants');
  });

  it.each([
    [404, 'Loja não encontrada'],
    [409, 'já pertence a uma conta nesta loja'],
    [429, 'Muitas tentativas'],
    [503, 'Não foi possível confirmar o cadastro'],
  ])('shows customer error %i', async (status, message) => {
    await harness.navigateByUrl('/cadastro', RegisterComponent);
    fillAccount();
    submit();
    http.expectOne('/api/public/customer-auth/register').flush({}, { status, statusText: 'Error' });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain(message);
    expect(harness.routeNativeElement!.querySelector('button')!.disabled).toBe(false);
  });

  it('reports merchant conflicts without claiming which field is duplicated', async () => {
    await harness.navigateByUrl('/painel/cadastro', RegisterComponent);
    fillAccount();
    fill('#storeName', 'Loja');
    fill('#subdomain', 'loja');
    submit();
    http.expectOne('/api/merchants').flush({}, { status: 409, statusText: 'Conflict' });
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('E-mail ou endereço da loja já cadastrado');
  });
  it('does not silently claim redirection when the returned store address is missing', async () => {
    await harness.navigateByUrl('/painel/cadastro', RegisterComponent);
    fillAccount(); fill('#storeName', 'Loja'); fill('#subdomain', 'loja'); submit();
    http.expectOne('/api/merchants').flush({});
    harness.detectChanges();
    expect(open).not.toHaveBeenCalled();
    expect(harness.routeNativeElement!.textContent).toContain('Não repita o cadastro');
  });
});
