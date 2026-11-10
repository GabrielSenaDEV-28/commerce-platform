import { UI } from '../ui/ui';
import { CurrencyPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { CartService } from '../shop/cart.service';
import { PurchaseOptionsComponent } from '../shop/purchase-options.component';
import { watchUnsavedChanges } from '../unsaved-changes';
import { Page, PublicProduct } from '../shop/shop.models';

@Component({
  selector: 'app-storefront',
  imports: [...UI, RouterLink, CurrencyPipe, PurchaseOptionsComponent],
  templateUrl: './storefront.component.html',
  styleUrl: './storefront.component.css',
})
export class StorefrontComponent {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  @ViewChild('quickBuy', { read: ElementRef }) private dialog?: ElementRef<HTMLDialogElement>;
  protected readonly buying = signal<PublicProduct | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly catalog = signal<Page<PublicProduct> | null>(null);
  protected readonly page = signal(0);

  constructor() {
    watchUnsavedChanges(this);
    this.load(0);
  }
  hasUnsavedChanges(): boolean { return false; }
  isSaving(): boolean { return this.cart.busy(); }

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
