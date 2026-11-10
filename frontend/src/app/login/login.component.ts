import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService, loginError, safeReturnUrl } from '../auth/auth.service';
import { UI } from '../ui/ui';

@Component({
  selector: 'app-login',
  imports: [...UI, ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly destroy = inject(DestroyRef);
  private readonly forms = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  protected readonly platformHost = ['localhost', '127.0.0.1'].includes(this.document.location.hostname);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly expired = signal(false);
  protected readonly form = this.forms.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });
  protected readonly storeForm = this.forms.nonNullable.group({
    subdomain: ['', [Validators.required, Validators.pattern(/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/)]],
  });
  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe((params) => this.expired.set(params.get('expired') === '1'));
  }
  protected openStore(): void {
    if (this.storeForm.invalid) { this.storeForm.markAllAsTouched(); return; }
    const location = this.document.location;
    location.assign(`${location.protocol}//${this.storeForm.controls.subdomain.value}.localhost${location.port ? ':' + location.port : ''}/entrar`);
  }
  protected submit(): void {
    if (this.loading() || this.auth.accountKind()) return;
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.loading.set(true); this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.auth.loginStore(email.trim(), password).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (kind) => {
        this.form.controls.password.reset(); this.loading.set(false);
        const destination = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'), kind);
        void this.router.navigateByUrl(destination ?? (kind === 'merchant' ? '/painel/categorias?inicio=1' : '/conta/perfil'), { replaceUrl: true });
      },
      error: (error: unknown) => { this.loading.set(false); this.error.set(loginError(error, 'customer')); },
    });
  }
}
