import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, catchError, defer, finalize, firstValueFrom, from, map, of, shareReplay, tap, timeout } from 'rxjs';

export type AccountKind = 'merchant' | 'customer';

interface TokenResponse {
  accessToken: string;
  refreshToken?: string;
  tokenType: string;
  expiresIn: number;
}

interface Session {
  kind: AccountKind;
  tokens: TokenResponse;
  identity: string;
  browser: boolean;
}
interface BrowserSession extends TokenResponse { accountKind: AccountKind; identity: string }

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = signal<Session | null>(null);
  private readonly router = inject(Router);
  private expiryTimer?: ReturnType<typeof setTimeout>;
  private readonly changes = new Subject<'login' | 'logout' | 'expiry' | 'refresh'>();
  readonly sessionChanges = this.changes.asObservable();
  readonly redirectingAfterExpiry = signal(false);
  readonly accountKind = computed(() => this.session()?.kind ?? null);
  readonly customerIdentity = computed(() => {
    const current = this.session();
    return current?.kind === 'customer' ? current.identity : null;
  });
  readonly restorationError = signal('');
  readonly restoring = signal(false);
  private refreshRequest?: Observable<boolean>;
  private generation = 0;
  private readonly browserPath = '/api/public/store-auth/session';
  private readonly browserHeaders = { 'X-Store-Session': '1' };

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
  }

  expire(kind: AccountKind, token: string): void {
    if (this.session()?.kind !== kind || this.session()?.tokens.accessToken !== token) return;
    clearTimeout(this.expiryTimer);
    this.redirectingAfterExpiry.set(true);
    this.session.set(null);
    this.generation++;
    this.changes.next('expiry');
    const returnUrl = safeReturnUrl(this.router.url, kind);
    void this.router.navigate(['/entrar'], {
      queryParams: { expired: '1', returnUrl }, replaceUrl: true,
    }).finally(() => this.redirectingAfterExpiry.set(false));
  }

  customerAccessToken(): string | null {
    const current = this.session();
    return current?.kind === 'customer' ? current.tokens.accessToken : null;
  }
  merchantAccessToken(): string | null {
    const current = this.session();
    return current?.kind === 'merchant' ? current.tokens.accessToken : null;
  }

  login(kind: AccountKind, email: string, password: string): Observable<void> {
    return this.http.post<TokenResponse>(`${this.basePath(kind)}/login`, { email, password }).pipe(
      tap((tokens) => {
        this.generation++;
        this.startSession(kind, tokens, email);
      }),
      map(() => undefined),
    );
  }

  loginStore(email: string, password: string): Observable<AccountKind> {
    return this.browserRequest<BrowserSession>('login', { email, password }).pipe(
      tap((tokens) => {
        this.generation++;
        this.startSession(tokens.accountKind, tokens, tokens.identity, true);
      }),
      map((tokens) => tokens.accountKind),
    );
  }
  registerCustomer(name: string, email: string, password: string): Observable<void> {
    return this.browserRequest<BrowserSession>('register', { name, email, password }).pipe(
      tap((tokens) => {
        this.generation++;
        this.startSession(tokens.accountKind, tokens, tokens.identity, true);
      }), map(() => undefined),
    );
  }
  restore(): Observable<boolean> {
    if (this.refreshRequest) return this.refreshRequest;
    this.restoring.set(true);
    const generation = this.generation;
    this.refreshRequest = this.browserRequest<BrowserSession>('refresh', {}).pipe(
      map((tokens) => {
        if (generation !== this.generation) return false;
        const current = this.session();
        const same = current?.browser && current.kind === tokens.accountKind && current.identity === tokens.identity;
        if (current && !same) this.generation++;
        this.startSession(tokens.accountKind, tokens, tokens.identity, true, same ? 'refresh' : 'login');
        return true;
      }),
      catchError((error: unknown) => {
        if (generation !== this.generation) return of(false);
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.restorationError.set('');
          const current = this.session();
          if (current) this.expire(current.kind, current.tokens.accessToken);
          return of(false);
        }
        this.restorationError.set('Não foi possível renovar sua sessão. Confira a conexão e tente novamente; sua conta e sacola não foram apagadas.');
        return of(false);
      }),
      finalize(() => { this.restoring.set(false); this.refreshRequest = undefined; }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.refreshRequest;
  }
  usesBrowserSession(): boolean { return this.session()?.browser ?? false; }
  sessionVersion(): number { return this.generation; }
  private browserRequest<T>(action: 'login' | 'register' | 'refresh' | 'logout', body: object): Observable<T> {
    const request = () => this.http.post<T>(`${this.browserPath}/${action}`, body, { headers: this.browserHeaders }).pipe(timeout(10000));
    const locks = globalThis.navigator?.locks;
    return locks ? defer(() => from(locks.request('commerce.browser-session', () => firstValueFrom(request())))) : defer(request);
  }

  private startSession(kind: AccountKind, tokens: TokenResponse, identity: string, browser = false, change: 'login' | 'refresh' = 'login'): void {
    clearTimeout(this.expiryTimer);
    this.redirectingAfterExpiry.set(false);
    this.restorationError.set('');
    this.session.set({ kind, tokens, identity: browser ? identity : identity.trim().toLowerCase(), browser });
    this.changes.next(change);
    this.expiryTimer = setTimeout(() => {
      if (browser) this.restore().subscribe();
      else this.expire(kind, tokens.accessToken);
    }, Math.max(1000, (browser ? tokens.expiresIn - Math.min(60, tokens.expiresIn / 4) : tokens.expiresIn) * 1000));
  }

  logout(): Observable<void> {
    const current = this.session();
    if (!current) {
      throw new Error('Não há uma sessão ativa.');
    }
    const request = current.browser
      ? this.browserRequest<void>('logout', {})
      : this.http.post<void>(`${this.basePath(current.kind)}/logout`, { refreshToken: current.tokens.refreshToken });
    return request.pipe(tap(() => {
      this.generation++; clearTimeout(this.expiryTimer); this.session.set(null); this.restorationError.set(''); this.changes.next('logout');
    }));
  }

  private basePath(kind: AccountKind): string {
    return kind === 'merchant' ? '/api/auth' : '/api/public/customer-auth';
  }
}

export function safeReturnUrl(value: string | null, kind: AccountKind): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\r\n]/.test(value)) return null;
  const path = value.split(/[?#]/)[0];
  if (path === '/') return value;
  if (kind === 'merchant' && /^\/painel\/(perfil|categorias|produtos|pedidos|promocoes|vendas|funcionarios|personalizacao)(\/|$)/.test(path)) return value;
  if (kind === 'customer' && /^\/conta\/(perfil|enderecos|pedidos|pagamentos|seguranca)$/.test(path)) return value;
  if (kind === 'customer' && (path === '/checkout' || /^\/conta\/pedidos\/[^/]+$/.test(path))) return value;
  return null;
}

export function loginError(error: unknown, kind: AccountKind): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) return 'E-mail ou senha inválidos.';
    if (error.status === 409) return 'Há um conflito entre contas nesta loja. Entre em contato com o suporte para regularizar o acesso.';
    if (error.status === 429) return 'Muitas tentativas. Aguarde um pouco e tente novamente.';
    if (error.status === 404 && kind === 'customer') return 'Loja não encontrada. Confira o endereço acessado.';
    if (error.status === 0 || error.status >= 500) return 'Não foi possível conectar ao serviço. Tente novamente.';
  }
  return 'Não foi possível entrar. Verifique os dados e tente novamente.';
}
