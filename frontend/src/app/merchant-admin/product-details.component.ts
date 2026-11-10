import { UI } from '../ui/ui';
import { CurrencyPipe, DatePipe, DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of, Subscription, switchMap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { VariantOption, VariantOptionsComponent } from './variant-options.component';
import { watchUnsavedChanges } from '../unsaved-changes';

interface Page<T> { content: T[]; page: number; totalPages: number }
interface Product { id: string; itemTypeId: string; sku: string; name: string; description: string; price: number; active: boolean }
interface TypeItem { hasColor: boolean; hasSize: boolean; sizeFormat: string | null }
interface Variant {
  id: string; color: string | null; size: string | null; price: number | null; effectivePrice: number;
  shippingWeightKg: number | null; shippingWidthCm: number | null; shippingHeightCm: number | null; shippingLengthCm: number | null;
}
interface Stock { quantity: number }
interface Photo { id: string; url: string; position: number }
interface PendingMovement { key: string; product: string; variant: string; quantity: number; reason: string }
interface Movement { id: string; occurredAt: string; type: string; beforeQuantity: number; afterQuantity: number; reason: string }

@Component({
  selector: 'app-product-details',
  imports: [...UI, RouterLink, ReactiveFormsModule, CurrencyPipe, DatePipe, VariantOptionsComponent],
  templateUrl: './product-details.component.html',
  styleUrl: './merchant-admin.component.css',
})
export class ProductDetailsComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly destroy = inject(DestroyRef);
  private readonly forms = inject(FormBuilder);
  private readonly router = inject(Router);
  protected readonly id = this.route.snapshot.paramMap.get('id') ?? '';
  protected readonly variantId = this.route.snapshot.paramMap.get('variantId');
  private readonly movementKey = `inventory-pending:${this.id}`;
  private historyRequest?: Subscription;
  private variantsRequest?: Subscription;
  protected readonly product = signal<Product | null>(null);
  protected readonly type = signal<TypeItem | null>(null);
  protected readonly variants = signal<Page<Variant> | null>(null);
  protected readonly selectedVariant = signal<Variant | null>(null);
  protected readonly stocks = signal<Record<string, number>>({});
  protected readonly photos = signal<Photo[] | null>(null);
  protected readonly options = signal<VariantOption[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly optionError = signal('');
  protected readonly photoError = signal('');
  protected readonly message = signal('');
  protected readonly priceError = signal('');
  protected readonly shippingError = signal('');
  protected readonly stockError = signal('');
  protected readonly variantError = signal('');
  protected readonly history = signal<Page<Movement> | null>(null);
  protected readonly historyLoading = signal(false);
  protected readonly historyPage = signal(0);
  protected readonly historyError = signal('');
  protected readonly preview = signal('');
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly deletingPhoto = signal<string | null>(null);
  protected readonly photosNeedReload = signal(false);
  protected readonly priceForm = this.forms.nonNullable.group({ price: [''] });
  protected readonly movementForm = this.forms.nonNullable.group({ quantity: [''], reason: [''] });
  protected readonly addForm = this.forms.nonNullable.group({ color: [''], size: [''] });
  protected readonly shippingForm = this.forms.nonNullable.group({
    weightKg: ['', [Validators.required, Validators.pattern(/^\d{1,5}(\.\d{1,3})?$/)]],
    widthCm: ['', [Validators.required, Validators.pattern(/^\d{1,6}(\.\d{1,2})?$/)]],
    heightCm: ['', [Validators.required, Validators.pattern(/^\d{1,6}(\.\d{1,2})?$/)]],
    lengthCm: ['', [Validators.required, Validators.pattern(/^\d{1,6}(\.\d{1,2})?$/)]],
  });
  protected readonly submitted = signal(false);
  protected readonly pending = signal<PendingMovement | null>(null);
  protected readonly pendingUnavailable = signal(false);
  protected readonly active = signal(true);
  protected readonly fieldError = signal<Record<string, string>>({});
  protected readonly editingPrice = signal(this.route.snapshot.queryParamMap.get('visualizar') !== '1');
  protected readonly editingShipping = signal(this.route.snapshot.queryParamMap.get('visualizar') !== '1');
  protected readonly editingStock = signal(this.route.snapshot.queryParamMap.get('visualizar') !== '1');

  private showSavedView(): void {
    if (this.hasUnsavedChanges() || this.pending()) return;
    this.editingPrice.set(false); this.editingShipping.set(false); this.editingStock.set(false);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { visualizar: '1' }, replaceUrl: true });
  }

  constructor() {
    watchUnsavedChanges(this);
    this.destroy.onDestroy(() => { this.clearPreview(); this.historyRequest?.unsubscribe(); this.variantsRequest?.unsubscribe(); });
    try {
      const saved = this.document.defaultView?.sessionStorage.getItem(this.movementKey);
      if (saved) {
        const parsed: unknown = JSON.parse(saved);
        if (this.isPending(parsed)) this.pending.set(parsed);
        else { this.pendingUnavailable.set(true); this.error.set('Há uma operação pendente inválida no navegador. Não lance outra entrada sem conferir o estoque.'); }
      }
    } catch {
      this.pendingUnavailable.set(true);
      this.error.set('Não foi possível ler operações pendentes. Não lance estoque sem conferir o histórico.');
    }
    this.http.get<{ subdomain: string }>('/api/store/me', { headers: this.headers() }).pipe(
      switchMap((store) => {
        if (this.document.location.hostname.toLowerCase() !== `${store.subdomain}.localhost`) {
          throw new Error('Acesse a administração no endereço da sua loja.');
        }
        return forkJoin({
          product: this.http.get<Product>(this.path(), { headers: this.headers() }),
          options: this.variantId ? of([] as VariantOption[]) : this.http.get<VariantOption[]>('/api/variant-options', { headers: this.headers() }),
          photos: this.variantId ? of(null) : this.http.get<Photo[]>(`${this.path()}/photos`, { headers: this.headers() }).pipe(
            catchError(() => { this.photoError.set('Não foi possível carregar as fotos. Recarregue a página para tentar novamente.'); return of(null); }),
          ),
        });
      }),
      switchMap(({ product, options, photos }) => {
        this.product.set(product); this.options.set(options); this.photos.set(photos);
        return this.variantId ? of(null) : this.http.get<TypeItem>(`/api/item-types/${product.itemTypeId}`, { headers: this.headers() });
      }), takeUntilDestroyed(this.destroy),
    ).subscribe({
      next: (type) => { this.type.set(type); if (this.variantId) this.loadVariant(); else this.loadVariants(0); },
      error: (error: unknown) => {
        this.loading.set(false);
        if (error instanceof HttpErrorResponse && error.status === 404) void this.router.navigateByUrl('/nao-encontrado', { replaceUrl: true });
        else this.error.set(error instanceof Error && !(error instanceof HttpErrorResponse) ? error.message : 'Não foi possível carregar o produto. Confira a sessão e tente novamente.');
      },
    });
  }
  hasUnsavedChanges(): boolean {
    return this.addForm.dirty || this.priceForm.dirty || this.shippingForm.dirty || this.movementForm.dirty || !!this.selectedFile();
  }
  isSaving(): boolean { return this.busy(); }
  hasUnconfirmedOperation(): boolean { return !!this.pending(); }
  private isPending(value: unknown): value is PendingMovement {
    if (!value || typeof value !== 'object') return false;
    const obj = value as Record<string, unknown>;
    return typeof obj['key'] === 'string' && obj['product'] === this.id && typeof obj['variant'] === 'string'
      && typeof obj['quantity'] === 'number' && Number.isInteger(obj['quantity']) && obj['quantity'] > 0 && typeof obj['reason'] === 'string';
  }
  private headers() { return new HttpHeaders({ Authorization: `Bearer ${this.auth.merchantAccessToken()}` }); }
  private path() { return `/api/products/${encodeURIComponent(this.id)}`; }
  private loadVariant(): void {
    if (!this.variantId) return;
    this.loading.set(true);
    forkJoin({
      variant: this.http.get<Variant & { active: boolean }>(`${this.path()}/variants/${encodeURIComponent(this.variantId)}`, { headers: this.headers() }),
      stock: this.http.get<Stock>(`/api/inventory/products/${encodeURIComponent(this.id)}/variants/${encodeURIComponent(this.variantId)}`, { headers: this.headers() }),
    }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: ({ variant, stock }) => {
        this.selectedVariant.set(variant); this.active.set(variant.active); this.stocks.set({ [variant.id]: stock.quantity });
        if (!this.priceForm.dirty) this.priceForm.reset({ price: variant.price?.toString() ?? '' });
        if (!this.shippingForm.dirty) this.shippingForm.reset({ weightKg: variant.shippingWeightKg?.toString() ?? '', widthCm: variant.shippingWidthCm?.toString() ?? '',
          heightCm: variant.shippingHeightCm?.toString() ?? '', lengthCm: variant.shippingLengthCm?.toString() ?? '' });
        this.loading.set(false);
        this.loadHistory(0);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (error instanceof HttpErrorResponse && error.status === 404) void this.router.navigateByUrl('/nao-encontrado', { replaceUrl: true });
        else this.error.set('Não foi possível carregar a variante ou seu estoque.');
      },
    });
  }
  protected loadHistory(page: number): void {
    if (!this.variantId) return;
    this.historyRequest?.unsubscribe();
    this.historyLoading.set(true); this.historyPage.set(page); this.historyError.set('');
    this.historyRequest = this.http.get<Page<Movement>>(`/api/inventory/products/${encodeURIComponent(this.id)}/variants/${encodeURIComponent(this.variantId)}/movements`,
      { headers: this.headers(), params: { page, size: 10 } }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (history) => { this.history.set(history); this.historyLoading.set(false); },
        error: () => { this.historyLoading.set(false); this.historyError.set('Não foi possível carregar o histórico de estoque.'); },
      });
  }
  protected movementLabel(type: string): string {
    return ({ ENTRY: 'Entrada', EXIT: 'Saída', COUNT: 'Contagem' } as Record<string, string>)[type] ?? type;
  }
  protected loadVariants(page: number): void {
    this.variantsRequest?.unsubscribe();
    this.loading.set(true);
    this.variantsRequest = this.http.get<Page<Variant>>(`${this.path()}/variants`, { headers: this.headers(), params: { page, size: 20, active: this.active() } })
      .pipe(switchMap((variants) => {
        const stocks = variants.content.length ? forkJoin(variants.content.map((variant) =>
          this.http.get<Stock>(`/api/inventory/products/${this.id}/variants/${variant.id}`, { headers: this.headers() }))) : of<Stock[]>([]);
        return stocks.pipe(map((stocks) => ({ variants, stocks })));
      }), takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ variants, stocks: balances }) => {
          this.variants.set(variants);
          const stocks: Record<string, number> = {};
          variants.content.forEach((v, index) => stocks[v.id] = balances[index].quantity);
          this.stocks.set(stocks);
          this.loading.set(false);
        },
        error: () => { this.loading.set(false); this.error.set('Não foi possível carregar variantes ou estoque.'); },
      });
  }
  protected addOption(option: { kind: 'COLOR' | 'SIZE'; name: string; colorHex: string | null }): void {
    const name = option.name.trim();
    if (!name || name.length > (option.kind === 'COLOR' ? 80 : 40)) { this.optionError.set('Informe um nome válido.'); return; }
    this.busy.set(true); this.optionError.set('');
    this.http.post<VariantOption>('/api/variant-options', { ...option, name }, { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (created) => { this.options.update((list) => [...list, created]); (created.kind === 'COLOR' ? this.addForm.controls.color : this.addForm.controls.size).setValue(created.name); this.addForm.markAsDirty(); this.busy.set(false); },
      error: () => { this.busy.set(false); this.optionError.set('Não foi possível salvar. Verifique se a opção já existe.'); },
    });
  }
  protected addVariant(): void {
    if (this.busy()) return;
    this.submitted.set(true);
    const type = this.type(); const value = this.addForm.getRawValue();
    if (!type || (type.hasColor && !value.color.trim()) || (type.hasSize && !value.size.trim())) return;
    this.busy.set(true); this.variantError.set(''); this.message.set('');
    this.http.post<Variant>(`${this.path()}/variants`, { color: type.hasColor ? value.color.trim() : null, size: type.hasSize ? value.size.trim() : null }, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (created) => {
          this.busy.set(false); this.addForm.reset(); this.submitted.set(false); this.message.set('Variante cadastrada.');
          if (this.hasUnsavedChanges()) this.loadVariants(0);
          else void this.router.navigate(['/painel/produtos', this.id, 'variantes', created.id], { queryParams: { visualizar: '1' } });
        },
        error: () => { this.busy.set(false); this.variantError.set('Não foi possível cadastrar a variante. Confira se a combinação já existe.'); },
      });
  }
  protected updatePrice(variant: Variant, price: string): void {
    if (this.busy()) return;
    if (price && !/^\d{1,10}(\.\d{1,2})?$/.test(price)) { this.fieldError.update((errors) => ({ ...errors, [`price-${variant.id}`]: 'Informe um preço válido ou deixe vazio para usar o preço padrão.' })); return; }
    this.fieldError.update((errors) => ({ ...errors, [`price-${variant.id}`]: '' }));
    this.busy.set(true); this.priceError.set(''); this.message.set('');
    this.http.put<Variant>(`${this.path()}/variants/${variant.id}`, { price: price ? Number(price) : null }, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (updated) => { this.busy.set(false); this.selectedVariant.set(updated); this.priceForm.markAsPristine(); this.editingPrice.set(false); this.message.set('Preço atualizado.'); this.showSavedView(); },
        error: () => { this.busy.set(false); this.priceError.set('Não foi possível atualizar o preço.'); },
      });
  }
  protected saveShipping(variant: Variant): void {
    if (this.busy()) return;
    for (const control of Object.values(this.shippingForm.controls)) {
      if (control.valid && Number(control.value) <= 0) control.setErrors({ positive: true });
    }
    if (this.shippingForm.invalid) { this.shippingForm.markAllAsTouched(); return; }
    const values = this.shippingForm.getRawValue();
    this.busy.set(true); this.shippingError.set(''); this.message.set('');
    this.http.put(`${this.path()}/variants/${variant.id}/shipping`,
      { weightKg: Number(values.weightKg), widthCm: Number(values.widthCm), heightCm: Number(values.heightCm), lengthCm: Number(values.lengthCm) },
      { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: () => {
          this.busy.set(false); this.shippingForm.markAsPristine(); this.editingShipping.set(false);
          this.selectedVariant.update((current) => current ? { ...current, shippingWeightKg: Number(values.weightKg), shippingWidthCm: Number(values.widthCm), shippingHeightCm: Number(values.heightCm), shippingLengthCm: Number(values.lengthCm) } : current);
          this.message.set('Medidas de envio atualizadas.'); this.showSavedView();
        },
        error: () => { this.busy.set(false); this.shippingError.set('Não foi possível salvar as medidas de envio.'); },
      });
  }
  protected setVariantActive(variant: Variant): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set('');
    const url = `${this.path()}/variants/${variant.id}`;
    const request = this.active()
      ? this.http.delete(url, { headers: this.headers() })
      : this.http.post(`${url}/reactivate`, {}, { headers: this.headers() });
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => { this.busy.set(false); this.message.set('Situação da variante atualizada.'); this.loadVariants(this.variants()?.page ?? 0); },
      error: () => { this.busy.set(false); this.error.set('Não foi possível alterar a situação da variante. Verifique se há reservas ativas.'); },
    });
  }
  protected startMovement(variant: Variant, quantity: string, reason: string): void {
    if (this.busy() || this.pending() || this.pendingUnavailable()) return;
    const units = Number(quantity);
    const quantityError = !/^\d+$/.test(quantity) || !Number.isInteger(units) || units < 1 || units > 2147483647;
    const reasonError = !reason.trim() || reason.trim().length > 500;
    this.fieldError.update((errors) => ({ ...errors,
      [`quantity-${variant.id}`]: quantityError ? 'Informe uma quantidade inteira positiva de até 2.147.483.647.' : '',
      [`reason-${variant.id}`]: reasonError ? 'Informe um motivo de até 500 caracteres.' : '',
    }));
    if (quantityError || reasonError) return;
    const pending = { key: crypto.randomUUID(), product: this.id, variant: variant.id, quantity: units, reason: reason.trim() };
    try {
      if (!this.document.defaultView) throw new Error('Storage unavailable');
      this.document.defaultView.sessionStorage.setItem(this.movementKey, JSON.stringify(pending));
    } catch {
      this.stockError.set('Não foi possível guardar a operação para uma tentativa segura. Nenhuma entrada foi enviada.'); return;
    }
    this.pending.set(pending); this.sendMovement();
  }
  protected sendMovement(): void {
    const pending = this.pending(); if (!pending || this.busy()) return;
    this.busy.set(true); this.stockError.set(''); this.message.set('');
    this.http.post(`/api/inventory/products/${this.id}/variants/${pending.variant}/movements`,
      { type: 'ENTRY', quantity: pending.quantity, reason: pending.reason }, { headers: this.headers().set('Idempotency-Key', pending.key) })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: () => {
          try { this.document.defaultView?.sessionStorage.removeItem(this.movementKey); }
          catch { this.stockError.set('A entrada foi confirmada, mas não foi possível limpar o registro pendente.'); this.busy.set(false); return; }
          this.pending.set(null); this.busy.set(false); this.movementForm.reset(); this.message.set('Entrada de estoque registrada.');
          this.http.get<Stock>(`/api/inventory/products/${encodeURIComponent(this.id)}/variants/${encodeURIComponent(this.variantId ?? pending.variant)}`,
            { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
              next: (stock) => {
                this.stocks.update((stocks) => ({ ...stocks, [this.variantId ?? pending.variant]: stock.quantity }));
                this.editingStock.set(false); this.showSavedView();
              },
              error: () => this.stockError.set('Entrada registrada, mas o saldo não pôde ser atualizado. Consulte o histórico antes de lançar outra entrada.'),
            });
          this.loadHistory(0);
        },
        error: () => { this.busy.set(false); this.stockError.set('Não foi possível confirmar a entrada. Use a mesma tentativa para evitar duplicação.'); },
      });
  }
  private clearPreview(): void {
    if (this.preview()) URL.revokeObjectURL(this.preview());
    this.preview.set('');
  }
  protected selectPhoto(input: HTMLInputElement): void {
    this.clearPreview(); this.selectedFile.set(null);
    this.fieldError.update((errors) => ({ ...errors, photo: '' }));
    const file = input.files?.[0];
    if (!file) return;
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024 || file.size === 0) {
      this.fieldError.update((errors) => ({ ...errors, photo: 'Escolha uma imagem JPEG, PNG ou WebP de até 10 MiB.' })); return;
    }
    this.selectedFile.set(file);
    this.preview.set(URL.createObjectURL(file));
  }
  protected cancelPhoto(input: HTMLInputElement): void {
    this.clearPreview(); this.selectedFile.set(null); input.value = '';
  }
  protected reloadPhotos(): void {
    if (this.busy()) return;
    this.busy.set(true); this.photoError.set(''); this.message.set('');
    this.http.get<Photo[]>(`${this.path()}/photos`, { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (photos) => { this.photos.set(photos); this.photosNeedReload.set(false); this.busy.set(false); },
      error: () => { this.photoError.set('Não foi possível atualizar as fotos. Tente novamente antes de alterar a galeria.'); this.photosNeedReload.set(true); this.busy.set(false); },
    });
  }
  protected deletePhoto(photo: Photo): void {
    if (this.busy() || this.photosNeedReload()) return;
    this.busy.set(true); this.photoError.set(''); this.message.set('');
    this.http.delete(`${this.path()}/photos/${photo.id}`, { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => { this.photos.update((photos) => (photos ?? []).filter((item) => item.id !== photo.id).map((item, position) => ({ ...item, position }))); this.deletingPhoto.set(null); this.busy.set(false); this.message.set('Foto removida.'); },
      error: () => { this.busy.set(false); this.photosNeedReload.set(true); this.photoError.set('Não foi possível confirmar a remoção. Atualize a galeria antes de tentar novamente.'); },
    });
  }
  protected makeCover(photo: Photo): void {
    if (this.busy() || this.photosNeedReload()) return;
    const photos = this.photos(); if (!photos?.length) return;
    const photoIds = [photo.id, ...photos.filter((item) => item.id !== photo.id).map((item) => item.id)];
    this.busy.set(true); this.photoError.set(''); this.message.set('');
    this.http.put<Photo[]>(`${this.path()}/photos/order`, { photoIds }, { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (ordered) => { this.photos.set(ordered); this.busy.set(false); this.message.set('Foto principal atualizada.'); },
      error: () => { this.busy.set(false); this.photosNeedReload.set(true); this.photoError.set('Não foi possível confirmar a alteração da capa. Atualize a galeria antes de tentar novamente.'); },
    });
  }
  protected upload(input: HTMLInputElement): void {
    if (this.busy() || this.photosNeedReload()) return;
    const file = input.files?.[0];
    if (!file) { this.fieldError.update((errors) => ({ ...errors, photo: 'Selecione uma imagem.' })); return; }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024 || file.size === 0) {
      this.fieldError.update((errors) => ({ ...errors, photo: 'Escolha uma imagem JPEG, PNG ou WebP de até 10 MiB.' })); return;
    }
    const body = new FormData(); body.append('file', file);
    this.busy.set(true); this.message.set(''); this.fieldError.update((errors) => ({ ...errors, photo: '' }));
    this.http.post<Photo>(`${this.path()}/photos`, body, { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (photo) => { this.photos.update((photos) => [...(photos ?? []), photo]); this.cancelPhoto(input); this.busy.set(false); this.message.set('Foto adicionada.'); },
      error: () => { this.busy.set(false); this.photosNeedReload.set(true); this.fieldError.update((errors) => ({ ...errors, photo: 'Não foi possível confirmar o envio. Atualize a galeria e confira se a imagem já foi adicionada antes de repetir.' })); },
    });
  }
}
