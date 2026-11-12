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
  function browserLogin(): void {
    auth.loginStore('user@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({
      accessToken: 'browser-token', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant', identity: 'merchant-id',
    });
  }
  it('renews once for simultaneous authenticated 401s and retries with the new access token', () => {
    browserLogin();
    const received = vi.fn();
    for (const url of ['/api/one', '/api/two']) {
      client.get(url, { headers: { Authorization: 'Bearer browser-token' } }).subscribe(received);
    }
    http.expectOne('/api/one').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/two').flush({}, { status: 401, statusText: 'Unauthorized' });
    const refresh = http.expectOne('/api/public/store-auth/session/refresh');
    refresh.flush({ accessToken: 'renewed', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant', identity: 'merchant-id' });
    for (const url of ['/api/one', '/api/two']) {
      const retry = http.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer renewed');
      retry.flush({});
    }
    expect(received).toHaveBeenCalledTimes(2);
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled();
  });
  it('never retries uncertain writes and preserves the session on renewal infrastructure failure', () => {
    browserLogin();
    client.post('/api/write', {}, { headers: { Authorization: 'Bearer browser-token' } }).subscribe({ error: () => undefined });
    http.expectOne('/api/write').error(new ProgressEvent('error'));
    http.expectNone('/api/public/store-auth/session/refresh');
    client.get('/api/private', { headers: { Authorization: 'Bearer browser-token' } }).subscribe({ error: () => undefined });
    http.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/public/store-auth/session/refresh').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.merchantAccessToken()).toBe('browser-token');
    expect(auth.restorationError()).not.toBe('');
    http.expectNone('/api/private');
  });
  it('does not renew recursively when the cookie or retried access is rejected', () => {
    browserLogin();
    client.get('/api/private', { headers: { Authorization: 'Bearer browser-token' } }).subscribe({ error: () => undefined });
    http.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/public/store-auth/session/refresh').flush({ accessToken: 'renewed', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant', identity: 'merchant-id' });
    http.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectNone('/api/public/store-auth/session/refresh');
    expect(auth.accountKind()).toBeNull();
  });
  it('does not replay a request under another account restored from a changed cookie', () => {
    browserLogin();
    client.post('/api/write', {}, { headers: { Authorization: 'Bearer browser-token' } }).subscribe({ error: () => undefined });
    http.expectOne('/api/write').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/public/store-auth/session/refresh').flush({ accessToken: 'other', tokenType: 'Bearer', expiresIn: 900, accountKind: 'merchant', identity: 'other-merchant' });
    http.expectNone('/api/write');
    expect(auth.merchantAccessToken()).toBe('other');
  });
});
