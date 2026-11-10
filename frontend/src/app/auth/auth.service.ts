import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';

export type AccountKind = 'merchant' | 'customer';

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

interface Session {
  kind: AccountKind;
  tokens: TokenResponse;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = signal<Session | null>(null);
  private readonly router = inject(Router);
  private expiryTimer?: ReturnType<typeof setTimeout>;
  readonly redirectingAfterExpiry = signal(false);
  readonly accountKind = computed(() => this.session()?.kind ?? null);

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
  }

  expire(kind: AccountKind, token: string): void {
    if (this.session()?.kind !== kind || this.session()?.tokens.accessToken !== token) return;
    clearTimeout(this.expiryTimer);
    this.redirectingAfterExpiry.set(true);
    this.session.set(null);
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
        this.startSession(kind, tokens);
      }),
      map(() => undefined),
    );
  }

  loginStore(email: string, password: string): Observable<AccountKind> {
    return this.http.post<TokenResponse & { accountKind: AccountKind }>('/api/public/store-auth/login', { email, password }).pipe(
      tap((tokens) => {
        this.startSession(tokens.accountKind, tokens);
      }),
      map((tokens) => tokens.accountKind),
    );
  }

  private startSession(kind: AccountKind, tokens: TokenResponse): void {
    clearTimeout(this.expiryTimer);
    this.redirectingAfterExpiry.set(false);
    this.session.set({ kind, tokens });
    this.expiryTimer = setTimeout(() => this.expire(kind, tokens.accessToken), tokens.expiresIn * 1000);
  }

  logout(): Observable<void> {
    const current = this.session();
    if (!current) {
      throw new Error('Não há uma sessão ativa.');
    }
    return this.http
      .post<void>(`${this.basePath(current.kind)}/logout`, { refreshToken: current.tokens.refreshToken })
      .pipe(tap(() => { clearTimeout(this.expiryTimer); this.session.set(null); }));
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
