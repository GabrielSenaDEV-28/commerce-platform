import { UI } from '../ui/ui';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { CartService, cartError } from '../shop/cart.service';
import { PurchaseOptionsComponent } from '../shop/purchase-options.component';
import { watchUnsavedChanges } from '../unsaved-changes';
import { Page, PublicProduct } from '../shop/shop.models';
import { MobileMenuComponent } from '../ui/mobile-menu.component';
import { FavoritesService } from '../shop/favorites.service';
import { ProductCardComponent } from '../shop/product-card.component';

@Component({
  selector: 'app-storefront',
  imports: [...UI, RouterLink, PurchaseOptionsComponent, MobileMenuComponent, ProductCardComponent],
  templateUrl: './storefront.component.html',
  styleUrl: './storefront.component.css',
})
export class StorefrontComponent {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  protected readonly signingOut = signal(false);
  protected readonly logoutError = signal('');
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  protected readonly favorites = inject(FavoritesService);
  protected readonly actionMessage = signal('');
  protected readonly actionError = signal('');
  protected readonly removing = signal<PublicProduct | null>(null);
  @ViewChild('removeConfirm', { read: ElementRef }) private removeDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('quickBuy', { read: ElementRef }) private dialog?: ElementRef<HTMLDialogElement>;
  protected readonly buying = signal<PublicProduct | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly catalog = signal<Page<PublicProduct> | null>(null);
  protected readonly page = signal(0);

  constructor() {
    watchUnsavedChanges(this);
    this.cart.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => { /* The global recovery banner provides retry actions. */ } });
    this.load(0);
  }
  hasUnsavedChanges(): boolean { return false; }
  isSaving(): boolean { return this.cart.busy() || this.signingOut(); }
  protected logout(): void {
    if (this.isSaving() || !this.auth.accountKind()) return;
    this.signingOut.set(true); this.logoutError.set('');
    this.auth.logout().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.signingOut.set(false); void this.router.navigateByUrl('/entrar'); },
      error: () => { this.signingOut.set(false); this.logoutError.set('Não foi possível encerrar a sessão. Tente novamente.'); },
    });
  }
  protected purchaseConfirmed(product: PublicProduct): void {
    this.closePurchase();
    this.actionMessage.set(`${product.name} adicionado à sacola.`);
  }
  protected cartAction(product: PublicProduct): void {
    if (this.cart.busy()) return;
    if (!this.cart.contains(product.id)) { this.openPurchase(product); return; }
    this.removing.set(product); this.actionError.set('');
    this.removeDialog?.nativeElement.showModal();
  }
  protected closeRemoval(event?: Event): void {
    if (this.cart.busy()) { event?.preventDefault(); return; }
    this.removeDialog?.nativeElement.close(); this.removing.set(null);
  }
  protected removeProduct(): void {
    const product = this.removing();
    if (!product || this.cart.busy()) return;
    this.actionError.set(''); this.actionMessage.set('');
    this.cart.removeProduct(product.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.actionMessage.set(`Todas as variantes de ${product.name} foram retiradas da sacola.`);
        this.removeDialog?.nativeElement.close(); this.removing.set(null);
      },
      error: (error: unknown) => this.actionError.set(`A remoção pode ter sido parcial. ${cartError(error)}`),
    });
  }
  protected toggleFavorite(product: PublicProduct): void {
    this.favorites.toggle(product.id);
    this.actionMessage.set(`${product.name} ${this.favorites.has(product.id) ? 'adicionado aos' : 'removido dos'} favoritos deste navegador.`);
  }

  protected openPurchase(product: PublicProduct): void {
    if (this.cart.busy()) return;
    this.buying.set(product);
    this.dialog?.nativeElement.showModal();
  }
  protected closePurchase(event?: Event): void {
    if (this.cart.busy()) { event?.preventDefault(); return; }
    this.dialog?.nativeElement.close();
    this.buying.set(null);
  }

  protected load(page: number): void {
    if (page < 0) return;
    this.page.set(page);
    this.loading.set(true);
    this.error.set('');
    this.http.get<Page<PublicProduct>>('/api/public/catalog/products', {
      params: { page, size: 12 },
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.catalog.set(result);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.catalog.set(null);
        this.error.set(error instanceof HttpErrorResponse && (error.status === 401 || error.status === 404)
          ? 'Não foi possível encontrar esta loja. Confira o endereço acessado.'
          : 'Não foi possível carregar o catálogo. Verifique sua conexão e tente novamente.');
        this.loading.set(false);
      },
    });
  }
}
