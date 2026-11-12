import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, catchError, concatMap, defer, finalize, from, last, of, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { Cart } from './shop.models';

@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly storageKey = 'commerce.anonymous-cart';
  private token: string | null = null;
  private accountToken = false;
  private revision = 0;
  private loadingRequest?: Observable<Cart | null>;
  readonly recoveryError = signal('');
  readonly notice = signal('');
  readonly canDiscardAnonymous = signal(false);
  readonly customer = computed(() => !!this.auth.customerAccessToken());
  readonly cart = signal<Cart | null>(null);
  readonly busy = signal(false);
  readonly count = computed(() => this.cart()?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0);
  constructor() {
    try { this.token = this.window?.sessionStorage.getItem(this.storageKey) ?? null; }
    catch { this.notice.set('O navegador não permitiu recuperar a sacola anônima desta aba. Entre na conta para recuperar a sacola do cliente.'); }
    this.auth.sessionChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((reason) => {
      if (reason === 'refresh') return;
      this.revision++;
      if (this.accountToken) { this.token = null; this.accountToken = false; this.cart.set(null); }
      this.recoveryError.set('');
      this.canDiscardAnonymous.set(false);
    });
  }
  private storeAnonymous(token: string | null): void {
    try {
      if (token) this.window?.sessionStorage.setItem(this.storageKey, token);
      else this.window?.sessionStorage.removeItem(this.storageKey);
      this.notice.set('');
    } catch { this.notice.set('O navegador bloqueou o armazenamento da sacola anônima. Ela só estará acessível até atualizar a página; a sacola vinculada à conta continua salva no servidor.'); }
  }
  private authorization(headers = new HttpHeaders()): HttpHeaders {
    const bearer = this.auth.customerAccessToken();
    return bearer ? headers.set('Authorization', `Bearer ${bearer}`) : headers;
  }
  headers(): HttpHeaders {
    if (!this.token) throw new Error('O carrinho ainda não foi criado.');
    if (this.auth.customerAccessToken() && !this.accountToken) throw new Error('Recupere a sacola da sua conta antes de continuar.');
    if (this.accountToken && !this.auth.customerAccessToken()) throw new Error('Entre novamente para acessar sua sacola.');
    return this.authorization(new HttpHeaders({ 'Cart-Token': this.token }));
  }
  exclusive<T>(request: () => Observable<T>): Observable<T> {
    return defer(() => {
      if (this.auth.restorationError()) return throwError(() => new Error(this.auth.restorationError()));
      if (this.busy()) return throwError(() => new Error('Aguarde a operação atual do carrinho.'));
      this.busy.set(true);
      return defer(request).pipe(finalize(() => this.busy.set(false)));
    });
  }
  private read(): Observable<Cart> {
    return this.http.get<Cart>('/api/public/cart', { headers: this.headers() }).pipe(this.updateCart());
  }
  private updateCart() {
    const revision = this.revision;
    return tap<Cart>((cart) => {
      if (revision !== this.revision) throw new Error('A sessão mudou. Entre novamente para recuperar sua sacola.');
      this.cart.set(cart);
    });
  }
  private recover(): Observable<Cart | null> {
    if (this.auth.customerAccessToken() && !this.accountToken) {
      const revision = this.revision;
      return this.http.post<{ token: string; cart: Cart }>('/api/public/cart/customer', {}, {
        headers: this.authorization(this.token ? new HttpHeaders({ 'Cart-Token': this.token }) : new HttpHeaders()),
      }).pipe(tap((result) => {
        if (revision !== this.revision) throw new Error('A sessão mudou durante a recuperação. Tente novamente.');
        this.token = result.token; this.accountToken = true;
        this.storeAnonymous(null); this.cart.set(result.cart);
      }), switchMap((result) => of(result.cart)));
    }
    return this.token ? this.read() : of(null);
  }
  load(): Observable<Cart | null> {
    if (this.loadingRequest) return this.loadingRequest;
    this.loadingRequest = this.exclusive(() => {
      this.recoveryError.set('');
      this.canDiscardAnonymous.set(false);
      return this.recover();
    }).pipe(catchError((error: unknown) => {
      this.recoveryError.set(cartError(error));
      this.canDiscardAnonymous.set(!this.accountToken && !!this.token && error instanceof HttpErrorResponse && error.status === 404);
      return throwError(() => error);
    }), finalize(() => this.loadingRequest = undefined), shareReplay({ bufferSize: 1, refCount: true }));
    return this.loadingRequest;
  }
  forgetExpiredAnonymous(): Observable<Cart | null> {
    if (this.accountToken || this.busy()) return throwError(() => new Error('Não é possível descartar a sacola atual.'));
    this.token = null; this.cart.set(null); this.storeAnonymous(null);
    return this.load();
  }
  add(productId: string, variantId: string, quantity: number): Observable<Cart> {
    return this.exclusive(() => {
      const recover = this.auth.customerAccessToken() && !this.accountToken ? this.recover() : of(null);
      return recover.pipe(switchMap(() => {
        if (this.token) return of(null);
        const revision = this.revision;
        return this.http.post<{ token: string }>('/api/public/cart', {}).pipe(tap((created) => {
          if (revision !== this.revision) throw new Error('A sessão mudou. Confira a sacola antes de repetir.');
          this.token = created.token; this.storeAnonymous(created.token);
        }));
      }), switchMap(() => this.read()), switchMap((cart) => {
        const existing = cart.items.find((item) => item.productId === productId && item.variantId === variantId);
        return this.write(productId, variantId, (existing?.quantity ?? 0) + quantity);
      }));
    });
  }
  setQuantity(productId: string, variantId: string, quantity: number): Observable<Cart> {
    return this.exclusive(() => this.write(productId, variantId, quantity));
  }
  private write(productId: string, variantId: string, quantity: number): Observable<Cart> {
    return this.http.put<Cart>(`/api/public/cart/items/${encodeURIComponent(productId)}/${encodeURIComponent(variantId)}`,
      { quantity }, { headers: this.headers() }).pipe(this.updateCart());
  }
  remove(productId: string, variantId: string): Observable<Cart> {
    return this.exclusive(() => this.deleteItem(productId, variantId));
  }
  private deleteItem(productId: string, variantId: string): Observable<Cart> {
    return this.http.delete<Cart>(`/api/public/cart/items/${encodeURIComponent(productId)}/${encodeURIComponent(variantId)}`,
      { headers: this.headers() }).pipe(this.updateCart());
  }
  contains(productId: string): boolean { return this.cart()?.items.some((item) => item.productId === productId) ?? false; }
  removeProduct(productId: string): Observable<Cart> {
    return this.exclusive(() => this.read().pipe(switchMap((cart) => {
      const items = cart.items.filter((item) => item.productId === productId);
      return items.length ? from(items).pipe(
        concatMap((item) => this.deleteItem(productId, item.variantId)),
        last(),
      ) : of(cart);
    })));
  }
}

export function cartError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 409) return 'O estoque mudou, o limite do carrinho foi atingido ou existe uma reserva em andamento. Atualize o carrinho antes de tentar novamente.';
    if (error.status === 401) return 'Sua sessão expirou. Entre novamente para recuperar sua sacola.';
    if (error.status === 403) return 'Esta sacola não pertence à conta atual. Entre na conta correta; seus itens não foram descartados.';
    if (error.status === 404) return 'A sacola anônima expirou ou o item não está mais disponível. Confira a sacola antes de iniciar outra.';
    if (error.status === 429) return 'Muitas tentativas. Aguarde um pouco antes de continuar.';
  }
  return 'Não foi possível confirmar a alteração. Confira o carrinho antes de repetir: a operação pode ter sido concluída.';
}
