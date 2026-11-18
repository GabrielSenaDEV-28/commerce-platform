import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { onTestFinished, vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { CartRecoveryComponent } from './cart-recovery.component';
import { CartService } from './cart.service';
import { ToastService } from '../ui/toast.service';

describe('global cart recovery', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });
  it('recovers after login, exposes failures globally and retries without losing the source', () => {
    sessionStorage.setItem('commerce.anonymous-cart', 'anonymous');
    const fixture = TestBed.createComponent(CartRecoveryComponent); fixture.detectChanges();
    http.expectOne('/api/public/cart').flush({ items: [] });
    TestBed.inject(AuthService).loginStore('customer@example.com', 'password').subscribe();
    http.expectOne('/api/public/store-auth/session/login').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', refreshToken: 'r', expiresIn: 900 });
    fixture.detectChanges();
    http.expectOne('/api/public/cart/customer').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[role="alert"]')).not.toBeNull();
    expect(root.textContent).toContain('Tentar recuperar sacola');
    [...root.querySelectorAll('button')].find((button) => button.textContent?.includes('Tentar recuperar sacola'))!.click();
    const retry = http.expectOne('/api/public/cart/customer');
    expect(retry.request.headers.get('Cart-Token')).toBe('anonymous');
    retry.flush({ token: 'account', cart: { items: [] } }); fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(TestBed.inject(CartService).recoveryError()).toBe('');
  });
  it('does not replace an expired anonymous cart until the visitor chooses to discard its access', () => {
    sessionStorage.setItem('commerce.anonymous-cart', 'expired');
    const fixture = TestBed.createComponent(CartRecoveryComponent); fixture.detectChanges();
    http.expectOne('/api/public/cart').flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBe('expired');
    const root: HTMLElement = fixture.nativeElement;
    [...root.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.startsWith('Descartar'))!.click();
    fixture.detectChanges();
    expect(sessionStorage.getItem('commerce.anonymous-cart')).toBeNull();
    expect(root.querySelector('[role="alert"]')).toBeNull();
    http.expectNone('/api/public/cart');
  });
  it('does not load or merge the cart again when the same identity refreshes', () => {
    const auth = TestBed.inject(AuthService);
    auth.restore().subscribe();
    const session = { accountKind: 'customer', identity: 'customer-id', accessToken: 'access', expiresIn: 900 };
    http.expectOne('/api/public/store-auth/session/refresh').flush(session);
    const fixture = TestBed.createComponent(CartRecoveryComponent); fixture.detectChanges();
    http.expectOne('/api/public/cart/customer').flush({ token: 'account', cart: { items: [] } });
    auth.restore().subscribe();
    fixture.detectChanges();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ ...session, accessToken: 'renewed' });
    fixture.detectChanges();
    http.expectNone('/api/public/cart/customer'); http.expectNone('/api/public/cart');
  });
  it('blocks anonymous cart changes after a restoration outage and offers session retry', () => {
    sessionStorage.setItem('commerce.anonymous-cart', 'anonymous');
    const auth = TestBed.inject(AuthService);
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({}, { status: 503, statusText: 'Unavailable' });
    const fixture = TestBed.createComponent(CartRecoveryComponent); fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Não foi possível conectar');
    TestBed.inject(CartService).add('p1', 'v1', 1).subscribe({ error: () => undefined });
    http.expectNone('/api/public/cart');
    [...root.querySelectorAll('button')].find((button) => button.textContent?.includes('Tentar recuperar sessão'))!.click();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ accountKind: 'customer', identity: 'customer-id', accessToken: 'access', expiresIn: 900 });
    fixture.detectChanges();
    const merge = http.expectOne('/api/public/cart/customer');
    expect(merge.request.headers.get('Cart-Token')).toBe('anonymous');
    merge.flush({ token: 'account', cart: { items: [] } }); fixture.detectChanges();
    expect(root.textContent).not.toContain('Não foi possível conectar');
  });

  it('stacks distinct notices, replaces duplicates, and closes just the selected toast', () => {
    const notifications = TestBed.inject(ToastService);
    notifications.show('first', 'First error');
    notifications.show('second', 'Second error');
    notifications.show('first', 'Updated error');
    const fixture = TestBed.createComponent(CartRecoveryComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect([...root.querySelectorAll('[role="alert"]')].map((node) => node.textContent)).toEqual(['Updated error', 'Second error']);
    root.querySelector<HTMLButtonElement>('[aria-label="Fechar notificação"]')!.click();
    fixture.detectChanges();
    expect([...root.querySelectorAll('[role="alert"]')].map((node) => node.textContent)).toEqual(['Second error']);
  });

  it('opens the session dialog only for a confirmed expiry and navigates on click', async () => {
    const prototype = Object.getPrototypeOf(document.createElement('dialog'));
    const nativeShowModal = Object.getOwnPropertyDescriptor(prototype, 'showModal');
    Object.defineProperty(prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) { this.open = true; },
    });
    onTestFinished(() => {
      if (nativeShowModal) Object.defineProperty(prototype, 'showModal', nativeShowModal);
      else Reflect.deleteProperty(prototype, 'showModal');
    });
    const auth = TestBed.inject(AuthService);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(CartRecoveryComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({}, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(root.querySelector('dialog')).toBeNull();
    auth.restore().subscribe();
    http.expectOne('/api/public/store-auth/session/refresh').flush({ code: 'STORE_SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    const dialog = root.querySelector('dialog')!;
    expect(dialog.open).toBe(true);
    expect(dialog.textContent).toContain('Sua sessão expirou');
    expect(navigate).not.toHaveBeenCalled();
    dialog.querySelector('button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/entrar'], { queryParams: { returnUrl: null }, replaceUrl: true });
    expect(root.querySelector('dialog')).toBeNull();
  });
});
