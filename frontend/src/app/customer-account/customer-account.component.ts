import { UI } from '../ui/ui';
import { CurrencyPipe, DatePipe, DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { EMPTY, Observable, catchError, distinctUntilChanged, map, switchMap, tap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { confirmDiscard, watchUnsavedChanges } from '../unsaved-changes';
import { applyServerFieldErrors } from '../ui/validation';

interface Profile { name: string; email: string; phone: string | null }
interface AddressInput {
  recipient: string; postalCode: string; street: string; number: string;
  complement: string; district: string; city: string; state: string;
}
interface Address extends AddressInput { id: string; country: string }
interface OrderSummary { id: string; status: string; createdAt: string; total: number; fulfillmentType: string }
interface OrderPage { content: OrderSummary[]; page: number; totalPages: number }
type Section = 'perfil' | 'enderecos' | 'pedidos' | 'pagamentos' | 'seguranca';
const sections: Section[] = ['perfil', 'enderecos', 'pedidos', 'pagamentos', 'seguranca'];
const isSection = (value: string | null): value is Section => sections.some((section) => section === value);
const requiredText = (max: number) => [Validators.required, Validators.pattern(/\S/), Validators.maxLength(max)];

@Component({
  selector: 'app-customer-account',
  imports: [...UI, ReactiveFormsModule, RouterLink, RouterLinkActive, CurrencyPipe, DatePipe],
  templateUrl: './customer-account.component.html',
  styleUrl: './customer-account.component.css',
})
export class CustomerAccountComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  protected readonly section = signal<Section>('perfil');
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly success = signal('');
  protected readonly expired = signal(false);
  protected readonly profile = signal<Profile | null>(null);
  protected readonly addresses = signal<Address[]>([]);
  protected readonly orders = signal<OrderPage | null>(null);
  protected readonly editId = signal<string | null>(null);
  protected readonly editingAddress = signal(false);
  protected readonly deletingId = signal<string | null>(null);
  protected readonly editingProfile = signal(true);
  protected readonly states = ['AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO'];
  protected readonly profileForm = this.forms.nonNullable.group({
    name: ['', requiredText(150)], phone: ['', [Validators.required, Validators.pattern(/^\d{10,11}$/)]],
  });
  protected readonly addressForm = this.forms.nonNullable.group({
    recipient: ['', requiredText(150)], postalCode: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
    street: ['', requiredText(150)], number: ['', requiredText(30)],
    complement: ['', Validators.maxLength(150)], district: ['', requiredText(100)],
    city: ['', requiredText(100)], state: ['', [Validators.required, Validators.pattern(/^(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)$/)]],
  });

  constructor() {
    watchUnsavedChanges(this);
    this.route.paramMap.pipe(
      map((params) => params.get('section')),
      distinctUntilChanged(),
      tap((value) => {
        if (!isSection(value)) void this.router.navigateByUrl('/conta/perfil', { replaceUrl: true });
        this.section.set(isSection(value) ? value : 'perfil');
        this.error.set(''); this.success.set(''); this.editingAddress.set(false); this.deletingId.set(null);
      }),
      switchMap(() => this.fetchSection()),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  private headers(): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.auth.customerAccessToken()}` });
  }

  private fetchSection(): Observable<unknown> {
    this.error.set('');
    this.loading.set(true);
    const section = this.section();
    if (section === 'perfil') this.profile.set(null);
    if (section === 'enderecos') this.addresses.set([]);
    if (section === 'pedidos') this.orders.set(null);
    if (section === 'pagamentos' || section === 'seguranca') {
      this.loading.set(false);
      return EMPTY;
    }
    const request: Observable<unknown> = section === 'perfil'
      ? this.http.get<Profile>('/api/public/customer/profile', { headers: this.headers() }).pipe(tap((profile) => {
          this.profile.set(profile);
          this.profileForm.reset({ name: profile.name, phone: profile.phone ?? '' });
        }))
      : section === 'enderecos'
        ? this.http.get<Address[]>('/api/public/customer/addresses', { headers: this.headers() }).pipe(tap((addresses) => this.addresses.set(addresses)))
        : this.http.get<OrderPage>('/api/public/customer/orders?page=0&size=10', { headers: this.headers() }).pipe(tap((orders) => this.orders.set(orders)));
    return request.pipe(
      tap(() => this.loading.set(false)),
      catchError((error: unknown) => { this.handleError(error); this.loading.set(false); return EMPTY; }),
    );
  }

  protected retry(): void {
    if (this.hasUnsavedChanges() && !confirmDiscard(this.document.defaultView)) return;
    this.fetchSection().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  private handleError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 401) {
      this.expired.set(true);
      this.error.set('Sua sessão expirou ou não é mais válida. Encerre a sessão e entre novamente.');
    } else if (error instanceof HttpErrorResponse && error.status === 403) {
      this.error.set('Você não tem acesso a estes dados nesta loja.');
    } else if (error instanceof HttpErrorResponse && error.status === 409) {
      this.error.set('Não foi possível salvar. Verifique o limite de 10 endereços e tente novamente.');
    } else {
      this.error.set('Não foi possível concluir a operação. Confira os dados ou sua conexão e tente novamente.');
    }
  }

  protected saveProfile(): void {
    if (this.busy() || this.expired()) return;
    if (this.profileForm.invalid) { this.profileForm.markAllAsTouched(); return; }
    this.busy.set(true); this.error.set(''); this.success.set('');
    const value = this.profileForm.getRawValue();
    this.http.put<Profile>('/api/public/customer/profile', { name: value.name.trim(), phone: value.phone }, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (profile) => { this.profile.set(profile); this.profileForm.reset({ name: profile.name, phone: profile.phone ?? '' }); this.editingProfile.set(false); this.success.set('Dados atualizados.'); this.busy.set(false); },
        error: (error: unknown) => { if (!applyServerFieldErrors(this.profileForm, error)) this.handleError(error); this.busy.set(false); },
      });
  }

  hasUnsavedChanges(): boolean {
    return (this.section() === 'perfil' && this.profileForm.dirty) || (this.editingAddress() && this.addressForm.dirty);
  }
  isSaving(): boolean { return this.busy(); }
  protected cancelAddress(): void {
    if (!this.addressForm.dirty || confirmDiscard(this.document.defaultView)) this.editingAddress.set(false);
  }

  protected startAddress(address?: Address): void {
    this.editId.set(address?.id ?? null);
    this.addressForm.reset(address ? {
      recipient: address.recipient, postalCode: address.postalCode, street: address.street,
      number: address.number, complement: address.complement ?? '', district: address.district,
      city: address.city, state: address.state,
    } : undefined);
    this.error.set(''); this.success.set(''); this.editingAddress.set(true);
  }

  protected saveAddress(): void {
    if (this.busy() || this.expired()) return;
    if (this.addressForm.invalid) { this.addressForm.markAllAsTouched(); return; }
    this.busy.set(true); this.error.set(''); this.success.set('');
    const value = this.addressForm.getRawValue();
    const body: AddressInput = {
      ...value, recipient: value.recipient.trim(), street: value.street.trim(), number: value.number.trim(),
      complement: value.complement.trim(), district: value.district.trim(), city: value.city.trim(),
    };
    const id = this.editId();
    const path = '/api/public/customer/addresses';
    const request = id
      ? this.http.put<Address>(`${path}/${encodeURIComponent(id)}`, body, { headers: this.headers() })
      : this.http.post<Address>(path, body, { headers: this.headers() });
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (address) => {
        this.addresses.update((items) => id ? items.map((item) => item.id === id ? address : item) : [...items, address]);
        this.editingAddress.set(false); this.success.set('Endereço salvo.'); this.busy.set(false);
      },
      error: (error: unknown) => { if (!applyServerFieldErrors(this.addressForm, error)) this.handleError(error); this.busy.set(false); },
    });
  }

  protected deleteAddress(id: string): void {
    if (this.busy() || this.expired()) return;
    this.busy.set(true); this.error.set(''); this.success.set('');
    this.http.delete<void>(`/api/public/customer/addresses/${encodeURIComponent(id)}`, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => { this.addresses.update((items) => items.filter((item) => item.id !== id)); this.deletingId.set(null); this.success.set('Endereço removido.'); this.busy.set(false); },
        error: (error: unknown) => { this.handleError(error); this.busy.set(false); },
      });
  }

  protected loadOrders(page: number): void {
    if (this.loading() || page < 0) return;
    this.loading.set(true); this.error.set('');
    this.http.get<OrderPage>('/api/public/customer/orders', { headers: this.headers(), params: { page, size: 10 } })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (orders) => { this.orders.set(orders); this.loading.set(false); },
        error: (error: unknown) => { this.handleError(error); this.loading.set(false); },
      });
  }

  protected logout(): void {
    if (this.busy()) return;
    if (this.hasUnsavedChanges() && !confirmDiscard(this.document.defaultView)) return;
    this.busy.set(true); this.error.set('');
    this.auth.logout().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.profileForm.markAsPristine(); this.addressForm.markAsPristine(); this.busy.set(false); void this.router.navigateByUrl('/entrar'); },
      error: () => { this.error.set('Não foi possível encerrar a sessão. Tente novamente.'); this.busy.set(false); },
    });
  }
}
