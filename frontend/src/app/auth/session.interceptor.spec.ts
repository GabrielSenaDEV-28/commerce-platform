import { HttpClient, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from './auth.service';
import { sessionInterceptor } from './session.interceptor';

describe('session interceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(withInterceptors([sessionInterceptor])), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController); client = TestBed.inject(HttpClient); auth = TestBed.inject(AuthService);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    auth.login('merchant', 'user@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'current', refreshToken: 'refresh', expiresIn: 900 });
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); });
  it('clears the current session on an authenticated 401 only once', () => {
    client.get('/api/products', { headers: new HttpHeaders({ Authorization: 'Bearer current' }) }).subscribe({ error: () => undefined });
    http.expectOne('/api/products').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accountKind()).toBeNull();
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledTimes(1);
  });
  it.each([undefined, 'Bearer old'])('does not expire on public requests or an old token (%s)', (authorization) => {
    client.get('/api/products', { headers: authorization ? { Authorization: authorization } : {} }).subscribe({ error: () => undefined });
    http.expectOne('/api/products').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accountKind()).toBe('merchant');
  });
  it.each([403, 404, 500, 503])('preserves the session on HTTP %s', (status) => {
    client.get('/api/products', { headers: { Authorization: 'Bearer current' } }).subscribe({ error: () => undefined });
    http.expectOne('/api/products').flush({}, { status, statusText: 'Request failed' });
    expect(auth.accountKind()).toBe('merchant');
  });
  it('ignores a delayed 401 from a previous session', () => {
    client.get('/api/products', { headers: { Authorization: 'Bearer current' } }).subscribe({ error: () => undefined });
    const old = http.expectOne('/api/products');
    auth.login('merchant', 'user@example.com', 'password').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'new', refreshToken: 'new-refresh', expiresIn: 900 });
    old.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.merchantAccessToken()).toBe('new');
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled();
  });
});
