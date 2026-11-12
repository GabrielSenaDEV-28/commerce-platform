import { CurrencyPipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UI } from '../ui/ui';
import { PublicProduct } from './shop.models';

@Component({
  selector: 'app-product-card',
  imports: [...UI, RouterLink, CurrencyPipe],
  template: `
    <article class="product-card">
      <a class="product-destination" [routerLink]="['/produtos', product().id]" [attr.aria-label]="'Ver e comprar ' + product().name"></a>
      <div class="product-image">
        @if (product().photos.length) { <img [src]="product().photos[0]" [alt]="product().name" loading="lazy" /> }
        @else { <span aria-label="Produto sem foto">&#10035;</span> }
      </div>
      <div class="product-info"><h3>{{ product().name }}</h3><p>{{ product().description }}</p><strong>{{ product().price | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</strong><small>Preço padrão; variantes podem ter outro valor.</small></div>
      <div class="product-actions">
        <button class="catalog-add" [class.in-cart]="inCart()" type="button" [disabled]="busy()" (click)="cartAction.emit()">{{ inCart() ? 'Retirar do carrinho' : 'Adicionar ao carrinho' }}</button>
        <button class="favorite icon-action" type="button" [class.favorited]="favorited()" [attr.aria-pressed]="favorited()" [attr.aria-label]="(favorited() ? 'Desfavoritar ' : 'Favoritar ') + product().name" (click)="favoriteAction.emit()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z" /></svg>
        </button>
      </div>
    </article>`,
  styles: [`
    :host { display:block; min-width:0; }
    .product-card { position:relative; min-width:0; border-radius:8px; padding:8px; transition:background .18s,box-shadow .18s; }
    .product-card:hover, .product-card:focus-within { background:#f0f4ed; box-shadow:0 6px 20px #294b4010; }
    .product-destination { position:absolute; inset:0; border-radius:8px; z-index:1; }
    .product-image { aspect-ratio:4/5; background:#eff2ec; display:grid; place-items:center; overflow:hidden; color:#b7c8b7; font-size:3rem; }
    .product-image img { width:100%; height:100%; object-fit:cover; }
    .product-info h3 { font-size:.93rem; margin:17px 0 6px; }
    .product-info p { color:#6f7a72; font-size:.8rem; line-height:1.5; margin:0 0 12px; overflow-wrap:anywhere; }
    .product-info strong { display:block; font-size:.9rem; }
    .product-info small { display:block; color:#6f7a72; font-size:.68rem; margin-top:7px; }
    .product-actions { position:relative; z-index:2; display:flex; align-items:stretch; gap:6px; margin-top:16px; }
    .catalog-add { flex:1; min-width:0; font-size:.74rem; padding:10px 6px; }
    .catalog-add.in-cart { background:#294b40; color:white; border-color:#294b40; }
    .catalog-add.in-cart:not(:disabled):hover { background:#3b6654; color:white; }
    .favorite { display:grid; place-items:center; flex:0 0 44px; min-height:44px; padding:0; background:white; border:1px solid #d5e0d3; border-radius:6px; color:#315d42; }
    .favorite svg { width:20px; height:20px; fill:none; stroke:currentColor; stroke-width:1.4; }
    .favorite.favorited svg { fill:currentColor; }
    @media(max-width:720px) { .product-card { padding:5px; } .product-actions { gap:4px; } .catalog-add { font-size:.68rem; padding:8px 4px; } }
    @media(prefers-reduced-motion:reduce) { .product-card { transition:none; } }
  `],
})
export class ProductCardComponent {
  readonly product = input.required<PublicProduct>();
  readonly busy = input(false);
  readonly inCart = input(false);
  readonly favorited = input(false);
  readonly cartAction = output<void>();
  readonly favoriteAction = output<void>();
}
