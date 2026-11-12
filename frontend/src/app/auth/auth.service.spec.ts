import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Router } from '@angular/router';
import { AuthService, AccountKind, loginError, safeReturnUrl } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  const tokens = { accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it.each([
    ['merchant', '/api/auth'],
    ['customer', '/api/public/customer-auth'],
  ] as const)('uses the %s endpoints and keeps tokens in memory', (kind, basePath) => {
    const localStorage = vi.spyOn(Storage.prototype, 'setItem');
    auth.login(kind, 'user@example.com', 'password').subscribe();
    const login = http.expectOne(`${basePath}/login`);
    expect(login.request.method).toBe('POST');
    expect(login.request.body).toEqual({ email: 'user@example.com', password: 'password' });
    login.flush(tokens);
    expect(auth.accountKind()).toBe(kind);
    expect(localStorage).not.toHaveBeenCalled();

    auth.logout().subscribe();
    const logout = http.expectOne(`${basePath}/logout`);
    expect(logout.request.method).toBe('POST');
    expect(logout.request.body).toEqual({ refreshToken: 'refresh' });
    logout.flush(null);
    expect(auth.accountKind()).toBeNull();
  });

  it('retains the session when logout fails', () => {
    auth.login('merchant', 'user@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush(tokens);
    auth.logout().subscribe({ error: () => undefined });
    http.expectOne('/api/auth/logout').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.accountKind()).toBe('merchant');
  });

  it('does not create a session when login fails', () => {
    auth.login('customer', 'user@example.com', 'password').subscribe({ error: () => undefined });
    http.expectOne('/api/public/customer-auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accountKind()).toBeNull();
  });

  const browserTokens = { accessToken: 'browser-access', tokenType: 'Bearer', expiresIn: 900, accountKind: 'customer', identity: 'customer-id' };
  it('restores a browser session from its cookie without storing or exposing a refresh token', () => {
    const stored = vi.spyOn(Storage.prototype, 'setItem');
    const changes = vi.fn(); auth.sessionChanges.subscribe(changes);
    auth.restore().subscribe();
    const refresh = http.expectOne('/api/public/store-auth/session/refresh');
    expect(refresh.request.body).toEqual({});
    expect(refresh.request.headers.get('X-Store-Session')).toBe('1');
    expect(auth.restoring()).toBe(true);
    refresh.flush(browserTokens);
    expect(auth.customerIdentity()).toBe('customer-id');
    expect(auth.customerAccessToken()).toBe('browser-access');
    expect(auth.restoring()).toBe(false);
    expect(changes).toHaveBeenCalledWith('login');
    expect(stored).not.toHaveBeenCalled();
  });
  it('deduplicates refreshes and renews without restarting account recovery', () => {
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush(browserTokens);
    const changes = vi.fn(); auth.sessionChanges.subscribe(changes);
    auth.restore().subscribe(); auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ ...browserTokens, accessToken: 'renewed' });
    expect(auth.customerAccessToken()).toBe('renewed');
    expect(changes.mock.calls).toEqual([['refresh']]);
  });
  it('treats a missing cookie as a visitor but makes infrastructure failures visible and retryable', () => {
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accountKind()).toBeNull(); expect(auth.restorationError()).toBe('');
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.restorationError()).not.toBe('');
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush(browserTokens);
    expect(auth.accountKind()).toBe('customer'); expect(auth.restorationError()).toBe('');
  });
  it('keeps an existing session on refresh failure and revokes it through cookie logout', () => {
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush(browserTokens);
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').error(new ProgressEvent('error'));
    expect(auth.customerAccessToken()).toBe('browser-access');
    expect(auth.restorationError()).not.toBe('');
    auth.logout().subscribe();
    const logout = http.expectOne('/api/public/store-auth/session/logout');
    expect(logout.request.headers.get('X-Store-Session')).toBe('1');
    expect(logout.request.body).toEqual({});
    logout.flush(null);
    expect(auth.accountKind()).toBeNull(); expect(auth.restorationError()).toBe('');
  });
  it('does not restore a delayed refresh after confirmed logout', () => {
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush(browserTokens);
    auth.restore().subscribe();
    const delayed = http.expectOne('/api/public/store-auth/session/refresh');
    auth.logout().subscribe();
    http.expectOne('/api/public/store-auth/session/logout').flush(null);
    delayed.flush({ ...browserTokens, accessToken: 'too-late' });
    expect(auth.accountKind()).toBeNull();
  });
  it('renews before access expiration instead of logging out on the local timer', () => {
    vi.useFakeTimers();
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ ...browserTokens, expiresIn: 4 });
    vi.advanceTimersByTime(2999);
    http.expectNone('/api/public/store-auth/session/refresh');
    vi.advanceTimersByTime(1);
    http.expectOne('/api/public/store-auth/session/refresh').flush(browserTokens);
    expect(auth.accountKind()).toBe('customer');
  });

  it('expires the session at the duration returned by the server and preserves the destination', () => {
    vi.useFakeTimers();
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/painel/produtos/p1/variantes/v1');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    auth.login('merchant', 'user@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush({ ...tokens, expiresIn: 2 });
    vi.advanceTimersByTime(1999);
    expect(auth.accountKind()).toBe('merchant');
    vi.advanceTimersByTime(1);
    expect(auth.accountKind()).toBeNull();
    expect(auth.merchantAccessToken()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/entrar'], {
      queryParams: { expired: '1', returnUrl: '/painel/produtos/p1/variantes/v1' }, replaceUrl: true,
    });
  });

  it.each(['https://example.com', '//example.com', '/\\example.com', '/entrar', '/painel/login', '/conta/perfil'])(
    'rejects unsafe or inappropriate merchant return destination %s', (url) => {
      expect(safeReturnUrl(url, 'merchant')).toBeNull();
    },
  );

  it.each([
    [401, 'merchant', 'E-mail ou senha inválidos.'],
    [429, 'customer', 'Muitas tentativas. Aguarde um pouco e tente novamente.'],
    [404, 'customer', 'Loja não encontrada. Confira o endereço acessado.'],
    [404, 'merchant', 'Não foi possível entrar. Verifique os dados e tente novamente.'],
    [503, 'merchant', 'Não foi possível conectar ao serviço. Tente novamente.'],
  ] as [number, AccountKind, string][])('describes HTTP %i for %s', (status, kind, expected) => {
    expect(loginError(new HttpErrorResponse({ status }), kind)).toBe(expected);
  });
});
