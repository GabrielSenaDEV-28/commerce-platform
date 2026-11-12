import { UI } from '../ui/ui';
import { CurrencyPipe, DatePipe, DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { MobileMenuComponent } from '../ui/mobile-menu.component';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { forkJoin, Observable, Subscription } from 'rxjs';
import { VariantOption, VariantOptionsComponent } from './variant-options.component';
import { confirmDiscard, watchUnsavedChanges } from '../unsaved-changes';
import { applyServerFieldErrors } from '../ui/validation';
import { CopyLinkComponent } from '../ui/copy-link.component';

interface Page<T> { content: T[]; page: number; totalPages: number; totalElements: number }
interface TypeItem { id: string; name: string; hasColor: boolean; hasSize: boolean; sizeFormat: 'LETTER' | 'NUMERIC' | null; active: boolean }
interface Product { id: string; itemTypeId: string; sku: string; name: string; description: string; price: number; active: boolean }
interface Order { id: string; customerName: string; status: string; createdAt: string; total: number }
interface StoreInfo { name: string; subdomain: string }

@Component({
  selector: 'app-merchant-admin',
  imports: [...UI, ReactiveFormsModule, RouterLink, RouterLinkActive, CurrencyPipe, DatePipe, VariantOptionsComponent, CopyLinkComponent, NgTemplateOutlet, MobileMenuComponent],
  templateUrl: './merchant-admin.component.html',
  styleUrl: './merchant-admin.component.css',
})
export class MerchantAdminComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly destroy = inject(DestroyRef);
  private readonly forms = inject(FormBuilder);
  private listRequest?: Subscription;
  protected readonly section = signal('');
  protected readonly store = signal<StoreInfo | null>(null);
  protected readonly sameStore = signal(false);
  protected readonly localAddress = signal<string | null>(null);
  protected readonly storeUrl = signal('');
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly success = signal('');
  protected readonly list = signal<Page<TypeItem | Product | Order> | null>(null);
  protected readonly types = signal<TypeItem[]>([]);
  protected readonly options = signal<VariantOption[]>([]);
  protected readonly optionError = signal('');
  protected readonly active = signal(true);
  protected readonly editing = signal<string | null>(null);
  protected readonly formOpen = signal(false);
  protected readonly itemForm = this.forms.nonNullable.group({
    name: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(120)]],
    hasColor: [false], hasSize: [false], sizeFormat: ['LETTER'],
  });
  protected readonly productForm = this.forms.nonNullable.group({
    itemTypeId: ['', Validators.required], sku: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    name: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(150)]],
    description: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(5000)]],
    price: ['', [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    color: ['', Validators.maxLength(80)], size: ['', Validators.maxLength(40)],
  });

  constructor() {
    watchUnsavedChanges(this);
    this.destroy.onDestroy(() => this.listRequest?.unsubscribe());
    this.http.get<StoreInfo>('/api/store/me', { headers: this.headers() }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (store) => {
        this.store.set(store);
        const location = this.document.location;
        if (location.hostname === 'localhost' || location.hostname.endsWith('.localhost')) {
          this.localAddress.set(`${location.protocol}//${store.subdomain}.localhost${location.port ? ':' + location.port : ''}/entrar`);
          this.sameStore.set(location.hostname.toLowerCase() === `${store.subdomain}.localhost`);
          this.storeUrl.set(`${location.protocol}//${store.subdomain}.localhost${location.port ? ':' + location.port : ''}/`);
        } else {
          this.error.set('A identificação do endereço da loja ainda não está configurada para este ambiente.');
        }
        this.loading.set(false);
        if (this.sameStore() && this.route.snapshot.queryParamMap.get('inicio') === '1') {
          void this.router.navigateByUrl('/');
          return;
        }
        if (this.sameStore()) this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe((params) => {
          const section = params.get('section') ?? 'categorias';
          if (section !== this.section()) this.list.set(null);
          this.section.set(section);
          this.active.set(true); this.formOpen.set(false); this.error.set(''); this.success.set('');
          const editId = this.route.snapshot.queryParamMap.get('editar');
          if (editId && (this.section() === 'categorias' || this.section() === 'produtos')) {
            this.busy.set(true);
            const category = this.section() === 'categorias';
            const request: Observable<TypeItem | Product> = category ? this.http.get<TypeItem>(`/api/item-types/${encodeURIComponent(editId)}`, { headers: this.headers() })
              : this.http.get<Product>(`/api/products/${encodeURIComponent(editId)}`, { headers: this.headers() });
            request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
              next: (item) => { this.busy.set(false); if ('hasColor' in item) this.openItem(item); else this.openProduct(item); },
              error: (error: unknown) => { this.busy.set(false); this.handleError(error); },
            });
          } else this.load(0);
        });
      },
      error: (error: unknown) => { this.loading.set(false); this.handleError(error); },
    });
  }

  hasUnsavedChanges(): boolean { return this.formOpen() && (this.section() === 'categorias' ? this.itemForm.dirty : this.productForm.dirty); }
  isSaving(): boolean { return this.busy(); }
  protected closeForm(): void {
    if (!this.hasUnsavedChanges() || confirmDiscard(this.document.defaultView)) {
      this.formOpen.set(false);
      if (!this.list()) this.load(0);
    }
  }
  private headers(): HttpHeaders { return new HttpHeaders({ Authorization: `Bearer ${this.auth.merchantAccessToken()}` }); }
  private handleError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 401) this.error.set('A sessão expirou. Saia e entre novamente.');
    else if (error instanceof HttpErrorResponse && error.status === 409) this.error.set('Não foi possível concluir: há um conflito com dados existentes ou reservas ativas.');
    else this.error.set('Não foi possível concluir a operação. Confira os dados e tente novamente.');
  }
  protected load(page: number): void {
    this.listRequest?.unsubscribe();
    const section = this.section();
    if (!['categorias', 'produtos', 'pedidos'].includes(section)) { this.list.set(null); this.loading.set(false); return; }
    this.loading.set(true); this.error.set('');
    const path = section === 'categorias' ? '/api/item-types' : section === 'produtos' ? '/api/products' : '/api/orders';
    const params: Record<string, string | number | boolean> = { page, size: 20 };
    if (section !== 'pedidos') params['active'] = this.active();
    this.listRequest = this.http.get<Page<TypeItem | Product | Order>>(path, { headers: this.headers(), params }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (list) => { this.list.set(list); this.loading.set(false); },
      error: (error: unknown) => { this.loading.set(false); this.handleError(error); },
    });
  }
  protected setActive(value: boolean): void {
    if (this.loading() || this.active() === value) return;
    this.list.set(null); this.active.set(value); this.load(0);
  }
  protected openItem(item?: TypeItem): void {
    if (this.hasUnsavedChanges() && !confirmDiscard(this.document.defaultView)) return;
    this.editing.set(item?.id ?? null);
    this.itemForm.reset(item ? { name: item.name, hasColor: item.hasColor, hasSize: item.hasSize, sizeFormat: item.sizeFormat ?? 'LETTER' } : { name: '', hasColor: false, hasSize: false, sizeFormat: 'LETTER' });
    this.error.set(''); this.formOpen.set(true);
  }
  protected saveItem(): void {
    if (this.busy()) return;
    if (this.itemForm.invalid) { this.itemForm.markAllAsTouched(); return; }
    const value = this.itemForm.getRawValue();
    const body = { name: value.name.trim(), hasColor: value.hasColor, hasSize: value.hasSize, sizeFormat: value.hasSize ? value.sizeFormat : null };
    const id = this.editing();
    this.saveEntity(id ? this.http.put<TypeItem>(`/api/item-types/${id}`, body, { headers: this.headers() }) : this.http.post<TypeItem>('/api/item-types', body, { headers: this.headers() }), 'categorias');
  }
  protected openProduct(product?: Product): void {
    if (this.hasUnsavedChanges() && !confirmDiscard(this.document.defaultView)) return;
    this.busy.set(true); this.error.set(''); this.optionError.set('');
    forkJoin({
      options: this.http.get<VariantOption[]>('/api/variant-options', { headers: this.headers() }),
      page: this.http.get<Page<TypeItem>>('/api/item-types', { headers: this.headers(), params: { active: true, page: 0, size: 100 } }),
    })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ page, options }) => {
          this.busy.set(false); this.types.set(page.content); this.options.set(options); this.editing.set(product?.id ?? null);
          this.productForm.reset(product ? { itemTypeId: product.itemTypeId, sku: product.sku, name: product.name, description: product.description, price: String(product.price), color: '', size: '' } : undefined);
          this.formOpen.set(true);
          if (page.totalPages > 1) this.error.set('Mais de 100 categorias ativas: somente as primeiras 100 estão disponíveis neste formulário.');
        },
        error: (error: unknown) => { this.busy.set(false); this.handleError(error); },
      });
  }
  protected selectedType(): TypeItem | undefined { return this.types().find((item) => item.id === this.productForm.controls.itemTypeId.value); }
  protected addOption(option: { kind: 'COLOR' | 'SIZE'; name: string; colorHex: string | null }): void {
    const name = option.name.trim();
    if (!name || name.length > (option.kind === 'COLOR' ? 80 : 40)) { this.optionError.set('Informe um nome válido para a opção.'); return; }
    this.busy.set(true); this.optionError.set('');
    this.http.post<VariantOption>('/api/variant-options', { ...option, name }, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (created) => {
          this.options.update((options) => [...options, created]);
          (created.kind === 'COLOR' ? this.productForm.controls.color : this.productForm.controls.size).setValue(created.name);
          this.productForm.markAsDirty();
          this.busy.set(false);
        },
        error: (error: unknown) => {
          this.optionError.set(error instanceof HttpErrorResponse && error.status === 409
            ? 'Essa opção já está cadastrada.' : 'Não foi possível salvar a opção. Tente novamente.');
          this.busy.set(false);
        },
      });
  }
  protected saveProduct(): void {
    if (this.busy()) return;
    const id = this.editing();
    const value = this.productForm.getRawValue();
    const type = this.selectedType();
    if (this.productForm.invalid || (!id && (!type || (type.hasColor && !value.color.trim()) || (type.hasSize && !value.size.trim())))) {
      this.productForm.markAllAsTouched();
      if (!id && type?.hasColor && !value.color.trim()) this.productForm.controls.color.setErrors({ required: true });
      if (!id && type?.hasSize && !value.size.trim()) this.productForm.controls.size.setErrors({ required: true });
      return;
    }
    const fields = { sku: value.sku.trim(), name: value.name.trim(), description: value.description.trim(), price: Number(value.price) };
    this.saveEntity(id ? this.http.put<Product>(`/api/products/${id}`, fields, { headers: this.headers() })
      : this.http.post<Product>('/api/products', { ...fields, itemTypeId: value.itemTypeId, variants: [{ color: type?.hasColor ? value.color.trim() : null, size: type?.hasSize ? value.size.trim() : null }] }, { headers: this.headers() }), 'produtos');
  }
  private saveEntity(request: Observable<{ id: string }>, section: 'categorias' | 'produtos'): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set('');
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (saved) => {
        this.itemForm.markAsPristine(); this.productForm.markAsPristine(); this.formOpen.set(false); this.busy.set(false);
        void this.router.navigate(['/painel', section, saved.id]);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        if (!applyServerFieldErrors(section === 'categorias' ? this.itemForm : this.productForm, error)) this.handleError(error);
      },
    });
  }
  protected toggle(id: string): void {
    const path = this.section() === 'categorias' ? '/api/item-types' : '/api/products';
    const url = `${path}/${id}`;
    this.mutate(this.active() ? this.http.delete(url, { headers: this.headers() })
      : this.http.post(`${url}/reactivate`, {}, { headers: this.headers() }));
  }
  private mutate(request: Observable<unknown>): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(''); this.success.set('');
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => { this.busy.set(false); this.formOpen.set(false); this.success.set('Alteração salva.'); this.load(0); },
      error: (error: unknown) => { this.busy.set(false); this.handleError(error); },
    });
  }
  protected logout(): void {
    if (this.busy()) return;
    if (this.hasUnsavedChanges() && !confirmDiscard(this.document.defaultView)) return;
    this.busy.set(true);
    this.auth.logout().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => { this.formOpen.set(false); this.busy.set(false); void this.router.navigateByUrl('/entrar'); },
      error: () => { this.busy.set(false); this.error.set('Não foi possível sair. Tente novamente.'); },
    });
  }
  protected asType(item: TypeItem | Product | Order): TypeItem { return item as TypeItem; }
  protected asProduct(item: TypeItem | Product | Order): Product { return item as Product; }
  protected asOrder(item: TypeItem | Product | Order): Order { return item as Order; }
}
