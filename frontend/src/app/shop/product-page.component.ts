import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UI } from '../ui/ui';
import { CartService } from './cart.service';
import { PublicProduct } from './shop.models';
import { PurchaseOptionsComponent } from './purchase-options.component';
import { watchUnsavedChanges } from '../unsaved-changes';
import { ProductGalleryComponent } from './product-gallery.component';
import { CopyLinkComponent } from '../ui/copy-link.component';
import { FavoritesService } from './favorites.service';

@Component({
  selector: 'app-product-page',
  imports: [...UI, RouterLink, PurchaseOptionsComponent, ProductGalleryComponent, CopyLinkComponent],
  templateUrl: './product-page.component.html',
  styleUrl: './shop.css',
  styles: [`.product-description { display:flex; align-items:flex-start; gap:8px; margin:12px 0 20px; padding:0; border:0; } .product-description p { flex:1; min-width:0; margin:0; overflow-wrap:anywhere; } .product-description app-copy-link { flex-shrink:0; max-width:45%; }`],
})
export class ProductPageComponent {
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  protected readonly favorites = inject(FavoritesService);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  protected readonly product = signal<PublicProduct | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly productPath = `/produtos/${encodeURIComponent(this.id)}`;
  constructor() {
    watchUnsavedChanges(this);
    this.cart.load().pipe(takeUntilDestroyed(this.destroy)).subscribe({ error: () => { /* The global recovery banner provides retry actions. */ } });
    this.load();
  }
  hasUnsavedChanges(): boolean { return false; }
  isSaving(): boolean { return this.cart.busy(); }
  protected load(): void {
    if (this.loading()) return;
    this.loading.set(true); this.error.set('');
    this.http.get<PublicProduct>(`/api/public/catalog/products/${encodeURIComponent(this.id)}`)
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (product) => { this.product.set(product); this.loading.set(false); },
        error: (error: unknown) => {
          this.loading.set(false); this.error.set(error instanceof HttpErrorResponse && error.status === 404
            ? 'Este produto não está disponível nesta loja.' : 'Não foi possível carregar o produto. Confira sua conexão e tente novamente.');
        },
      });
  }
}
