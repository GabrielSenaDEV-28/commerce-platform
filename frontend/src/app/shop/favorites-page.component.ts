import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, from, map, mergeMap, of, toArray } from 'rxjs';
import { UI } from '../ui/ui';
import { FavoritesService } from './favorites.service';
import { PublicProduct } from './shop.models';
import { CurrencyPipe } from '@angular/common';

interface FavoriteEntry { id: string; product: PublicProduct | null; unavailable: boolean }

@Component({
  imports: [...UI, RouterLink, CurrencyPipe],
  template: `
    <header class="shop-header"><a class="brand" routerLink="/">c. <span>commerce</span></a><a routerLink="/carrinho">Minha sacola</a></header>
    <main class="shop-wrap">
      <nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/">Início</a><span>/</span><span aria-current="page">Meus favoritos</span></nav>
      <h1>Meus favoritos.</h1>
      <p class="muted">Salvos neste navegador, somente para esta loja. Não são sincronizados com sua conta ou outros dispositivos.</p>
      @if (favorites.error()) {
        <p class="field-error">{{ favorites.error() }}</p>
        <div class="actions"><button type="button" (click)="favorites.retry()">Tentar recuperar favoritos</button><button type="button" (click)="confirmClear.set(true)">Limpar favoritos locais</button></div>
      }
      @if (confirmClear()) {
        <section aria-label="Confirmar limpeza"><p>Remover todos os favoritos salvos neste navegador para esta loja?</p><div class="actions"><button type="button" (click)="favorites.clear(); confirmClear.set(false)">Confirmar limpeza</button><button type="button" (click)="confirmClear.set(false)">Cancelar</button></div></section>
      }
      @if (loading()) { <p role="status">Consultando seus favoritos...</p> }
      @if (!favorites.ids().length && !favorites.error()) {
        <section class="empty"><h2>Seus favoritos começam com um coração.</h2><p>Toque no coração de um produto para guardá-lo aqui.</p><a class="button" routerLink="/">Explorar produtos</a></section>
      }
      @for (entry of visible(); track entry.id) {
        @if (entry.product; as product) {
          <app-record-card [link]="['/produtos', product.id]" [label]="'Ver e comprar ' + product.name">
            <div class="favorite-summary">@if (product.photos.length) { <img [src]="product.photos[0]" [alt]="product.name" loading="lazy" /> }<div><h2>{{ product.name }}</h2><p>{{ product.description }}</p><strong>{{ product.price | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</strong><p>Preço padrão; confirme a variante na página do produto.</p></div></div>
            <button recordActions type="button" [attr.aria-label]="'Remover ' + product.name + ' dos favoritos'" (click)="remove(entry.id)">Remover dos favoritos</button>
          </app-record-card>
        } @else {
          <app-record-card [staticCard]="true" label="Favorito indisponível">
            <h2>{{ entry.unavailable ? 'Produto não disponível nesta loja' : 'Não foi possível consultar este favorito' }}</h2>
            <p>{{ entry.unavailable ? 'Ele pode ter sido desativado. Você pode mantê-lo salvo ou removê-lo.' : 'Seu favorito continua salvo. Tente consultar novamente.' }}</p>
            <div recordActions class="actions"><button type="button" [disabled]="loading()" (click)="reload(entry.id)">Consultar novamente</button><button type="button" (click)="remove(entry.id)">Remover dos favoritos</button></div>
          </app-record-card>
        }
      }
      <p role="status">{{ message() }}</p>
    </main>`,
  styleUrl: './shop.css',
  styles: [`
    .favorite-summary { display:flex; align-items:flex-start; gap:16px; }
    .favorite-summary img { width:80px; height:100px; object-fit:cover; border-radius:6px; }
    .favorite-summary h2 { font:400 1.3rem Georgia,serif; margin:0; }
    @media(max-width:480px) { .favorite-summary img { width:56px; height:70px; } .favorite-summary { gap:10px; } }
  `],
})
export class FavoritesPageComponent {
  protected readonly favorites = inject(FavoritesService);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly entries = signal<FavoriteEntry[]>([]);
  protected readonly visible = computed(() => this.entries().filter((entry) => this.favorites.has(entry.id)));
  protected readonly loading = signal(false);
  protected readonly message = signal('');
  protected readonly confirmClear = signal(false);
  constructor() {
    effect((cleanup) => {
      const ids = this.favorites.ids();
      const known = new Set(untracked(this.entries).map((entry) => entry.id));
      const missing = ids.filter((id) => !known.has(id));
      if (!missing.length) return;
      this.loading.set(true);
      const subscription = from(missing).pipe(
        mergeMap((id) => this.fetch(id), 4), toArray(), takeUntilDestroyed(this.destroy),
      ).subscribe((entries) => {
        this.entries.update((current) => [...current.filter((entry) => !missing.includes(entry.id)), ...entries]);
        this.loading.set(false);
      });
      cleanup(() => { subscription.unsubscribe(); this.loading.set(false); });
    });
  }
  private fetch(id: string) {
    return this.http.get<PublicProduct>(`/api/public/catalog/products/${encodeURIComponent(id)}`).pipe(
      map((product): FavoriteEntry => ({ id, product, unavailable: false })),
      catchError((error: unknown) => of<FavoriteEntry>({ id, product: null, unavailable: error instanceof HttpErrorResponse && error.status === 404 })),
    );
  }
  protected reload(id: string): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.fetch(id).pipe(takeUntilDestroyed(this.destroy)).subscribe((entry) => {
      this.entries.update((current) => current.map((item) => item.id === id ? entry : item));
      this.loading.set(false);
    });
  }
  protected remove(id: string): void {
    if (this.favorites.has(id)) this.favorites.toggle(id);
    this.message.set('Produto removido dos favoritos.');
  }
}
