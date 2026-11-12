import { UI } from '../ui/ui';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AccountKind, AuthService } from '../auth/auth.service';
import { watchUnsavedChanges } from '../unsaved-changes';
import { StoreNavigation } from '../auth/store-navigation.service';
import { applyServerFieldErrors } from '../ui/validation';
import { Observable } from 'rxjs';

@Component({
  selector: 'app-register',
  imports: [...UI, ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: '../login/login.component.css',
})
export class RegisterComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(FormBuilder);
  private readonly navigation = inject(StoreNavigation);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly kind = signal<AccountKind>(this.route.snapshot.data['kind']);
  protected readonly isMerchant = computed(() => this.kind() === 'merchant');
  protected readonly loading = signal(false);
  protected readonly complete = signal(false);
  protected readonly error = signal('');
  protected readonly storeAddress = signal<string | null>(null);
  protected readonly form = this.forms.nonNullable.group({
    name: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(150)]],
    storeName: [''],
    subdomain: [''],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(128), Validators.pattern(/\S/)]],
    confirmPassword: ['', Validators.required],
  });

  constructor() {
    watchUnsavedChanges(this);
    this.configureStoreFields();
    this.route.data.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((data) => {
      const kind: AccountKind = data['kind'];
      if (kind !== this.kind()) {
        this.kind.set(kind);
        this.form.reset();
        this.complete.set(false);
        this.storeAddress.set(null);
        this.error.set('');
        this.configureStoreFields();
      }
    });
  }

  hasUnsavedChanges(): boolean { return !this.complete() && this.form.dirty; }
  isSaving(): boolean { return this.loading(); }

  private configureStoreFields(): void {
    const { storeName, subdomain } = this.form.controls;
    storeName.setValidators(this.isMerchant() ? [Validators.required, Validators.pattern(/\S/)] : []);
    subdomain.setValidators(this.isMerchant() ? [
      Validators.required,
      Validators.maxLength(63),
      Validators.pattern(/^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/),
    ] : []);
    storeName.updateValueAndValidity();
    subdomain.updateValueAndValidity();
  }

  protected submit(): void {
    if (this.loading() || this.complete() || this.auth.accountKind() !== null) return;
    if (this.form.invalid || this.form.controls.password.value !== this.form.controls.confirmPassword.value) {
      this.form.markAllAsTouched();
      return;
    }

    const { name, storeName, subdomain, email, password } = this.form.getRawValue();
    const kind = this.kind();
    const body = kind === 'merchant'
      ? { merchantName: name.trim(), storeName: storeName.trim(), subdomain: subdomain.toLowerCase(), email: email.trim(), password }
      : { name: name.trim(), email: email.trim(), password };
    this.error.set('');
    this.loading.set(true);
    const registration: Observable<{ subdomain: string } | null | void> = kind === 'merchant'
      ? this.http.post<{ subdomain: string } | null>('/api/merchants', body)
      : this.auth.registerCustomer(name.trim(), email.trim(), password);
    registration.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (response) => {
        if (kind === 'merchant' && response && response.subdomain) {
          this.storeAddress.set(this.navigation.address(response.subdomain));
        }
        this.form.controls.password.reset();
        this.form.controls.confirmPassword.reset();
        this.complete.set(true);
        this.loading.set(false);
        if (kind === 'merchant' && this.storeAddress()) this.navigation.open(`${this.storeAddress()}/entrar`);
        else if (kind === 'merchant') this.error.set('A loja foi criada, mas seu endereço de acesso não pôde ser determinado neste ambiente. Não repita o cadastro; confira a configuração de domínio com o suporte.');
        else void this.router.navigateByUrl('/', { replaceUrl: true });
      },
      error: (error: unknown) => {
        if (!applyServerFieldErrors(this.form, error)) {
          if (error instanceof HttpErrorResponse && error.status === 409 && kind === 'customer') {
            this.form.controls.email.setErrors({ conflict: true }); this.form.controls.email.markAsTouched();
          } else this.error.set(registrationError(error, kind));
        }
        this.loading.set(false);
      },
    });
  }
}

export function registrationError(error: unknown, kind: AccountKind): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 409) return kind === 'merchant'
      ? 'E-mail ou endereço da loja já cadastrado.'
      : 'Este e-mail já está cadastrado nesta loja.';
    if (error.status === 404 && kind === 'customer') return 'Loja não encontrada. Confira o endereço acessado.';
    if (error.status === 400) return 'Confira os dados informados e tente novamente.';
    if (error.status === 429) return 'Muitas tentativas. Aguarde um pouco e tente novamente.';
    if (error.status === 0 || error.status >= 500) return 'Não foi possível confirmar o cadastro. Confira sua conexão antes de tentar novamente.';
  }
  return 'Não foi possível realizar o cadastro. Tente novamente.';
}
