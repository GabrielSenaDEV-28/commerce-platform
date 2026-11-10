import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, defer, finalize, of, switchMap, tap, throwError } from 'rxjs';
import { Cart } from './shop.models';

@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly http = inject(HttpClient);
  private token: string | null = null;
  readonly cart = signal<Cart | null>(null);
  readonly busy = signal(false);
  readonly count = computed(() => this.cart()?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0);
  headers(): HttpHeaders {
    if (!this.token) throw new Error('O carrinho ainda não foi criado.');
    return new HttpHeaders({ 'Cart-Token': this.token });
  }
  private exclusive<T>(request: () => Observable<T>): Observable<T> {
    return defer(() => {
      if (this.busy()) return throwError(() => new Error('Aguarde a operação atual do carrinho.'));
      this.busy.set(true);
      return request().pipe(finalize(() => this.busy.set(false)));
    });
  }
  private read(): Observable<Cart> {
    return this.http.get<Cart>('/api/public/cart', { headers: this.headers() }).pipe(tap((cart) => this.cart.set(cart)));
  }
  load(): Observable<Cart | null> {
    return this.exclusive(() => this.token ? this.read() : of(null));
  }
  add(productId: string, variantId: string, quantity: number): Observable<Cart> {
    return this.exclusive(() => {
      const create: Observable<unknown> = this.token ? of(null) : this.http.post<{ token: string }>('/api/public/cart', {}).pipe(tap((created) => this.token = created.token));
      return create.pipe(switchMap(() => this.read()), switchMap((cart) => {
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
      { quantity }, { headers: this.headers() }).pipe(tap((cart) => this.cart.set(cart)));
  }
  remove(productId: string, variantId: string): Observable<Cart> {
    return this.exclusive(() => this.http.delete<Cart>(`/api/public/cart/items/${encodeURIComponent(productId)}/${encodeURIComponent(variantId)}`,
      { headers: this.headers() }).pipe(tap((cart) => this.cart.set(cart))));
  }
}

export function cartError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 409) return 'O estoque mudou, o limite do carrinho foi atingido ou existe uma reserva em andamento. Atualize o carrinho antes de tentar novamente.';
    if (error.status === 404) return 'O carrinho expirou ou o item não está mais disponível. Atualize a página para iniciar outro carrinho.';
    if (error.status === 429) return 'Muitas tentativas. Aguarde um pouco antes de continuar.';
  }
  return 'Não foi possível confirmar a alteração. Confira o carrinho antes de repetir: a operação pode ter sido concluída.';
}
