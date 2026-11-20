import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../ui/toast.service';
import { UI } from '../ui/ui';
import { applyServerFieldErrors } from '../ui/validation';

interface ShippingSettings {
  postalCode: string; street: string; number: string; complement: string | null;
  district: string; city: string; state: string; pickupEnabled: boolean; pickupInstructions: string | null;
}
type ConnectionStatus = 'DISCONNECTED' | 'PENDING' | 'CONNECTED' | 'REFRESHING' | 'REAUTHORIZE';
const requiredText = (max: number) => [Validators.required, Validators.pattern(/\S/), Validators.maxLength(max)];

@Component({
  selector: 'app-delivery-settings',
  imports: [...UI, ReactiveFormsModule],
  templateUrl: './delivery-settings.component.html',
  styleUrl: './delivery-settings.component.css',
})
export class DeliverySettingsComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly destroy = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly notifications = inject(ToastService);
  protected readonly secure = this.document.location.protocol === 'https:';
  protected readonly states = ['AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO'];
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly connecting = signal(false);
  protected readonly statusLoading = signal(true);
  protected readonly status = signal<ConnectionStatus | null>(null);
  protected readonly loadError = signal('');
  protected readonly connectionError = signal('');
  protected readonly confirmingDisconnect = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{5}-?\d{3}$/)]],
    street: ['', requiredText(150)], number: ['', requiredText(30)],
    complement: ['', Validators.maxLength(150)], district: ['', requiredText(100)],
    city: ['', requiredText(100)], state: ['', [Validators.required, Validators.pattern(/^(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)$/)]],
    pickupEnabled: [false], pickupInstructions: ['', Validators.maxLength(1000)],
  });

  constructor() {
    const result = this.route.snapshot.queryParamMap.get('melhorEnvio');
    if (result) {
      if (result === 'connected') {
        this.notifications.show('shipping-oauth', 'Autorização concluída. Confira abaixo o estado da conexão.', 'info');
      } else {
        this.notify('Não foi possível concluir a autorização. Tente conectar novamente.');
      }
      void this.router.navigate([], {
        relativeTo: this.route, queryParams: { melhorEnvio: null }, queryParamsHandling: 'merge', replaceUrl: true,
        scroll: 'manual',
      });
    }
    this.load();
    this.refreshStatus();
  }

  hasUnsavedChanges(): boolean { return this.form.dirty; }
  isSaving(): boolean { return this.saving() || this.connecting(); }
  private headers(): HttpHeaders { return new HttpHeaders({ Authorization: `Bearer ${this.auth.merchantAccessToken()}` }); }
  private notify(message: string): void { this.notifications.show('delivery-settings', message); }

  protected load(): void {
    if (this.saving()) return;
    this.loading.set(true); this.loadError.set('');
    this.http.get<ShippingSettings>('/api/store/shipping-settings', { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (settings) => { this.reset(settings); this.loading.set(false); },
        error: (error: unknown) => {
          this.loading.set(false);
          if (error instanceof HttpErrorResponse && error.status === 404) return;
          this.loadError.set('Não foi possível carregar a origem da loja. Tente novamente antes de editar.');
          this.notify(this.loadError());
        },
      });
  }

  protected save(): void {
    if (this.isSaving() || this.loading() || this.loadError()) return;
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const value = this.form.getRawValue();
    const body: ShippingSettings = {
      ...value, postalCode: value.postalCode.replace('-', ''), street: value.street.trim(),
      number: value.number.trim(), district: value.district.trim(), city: value.city.trim(),
      complement: value.complement.trim() || null, pickupInstructions: value.pickupInstructions.trim() || null,
    };
    this.saving.set(true);
    this.http.put<ShippingSettings>('/api/store/shipping-settings', body, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (settings) => {
          this.reset(settings); this.saving.set(false);
          this.notifications.show('delivery-settings', 'Configurações de entrega salvas.', 'success');
        },
        error: (error: unknown) => {
          this.saving.set(false);
          if (!applyServerFieldErrors(this.form, error)) {
            this.notify('Não foi possível confirmar o salvamento. Seus dados continuam no formulário; tente novamente.');
          }
        },
      });
  }

  private reset(settings: ShippingSettings): void {
    this.form.reset({ ...settings, complement: settings.complement ?? '', pickupInstructions: settings.pickupInstructions ?? '' });
  }

  protected refreshStatus(): void {
    if (this.connecting()) return;
    this.statusLoading.set(true); this.connectionError.set('');
    this.http.get<{ status: ConnectionStatus }>('/api/integrations/melhor-envio', { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ status }) => { this.status.set(status); this.statusLoading.set(false); },
        error: () => {
          this.status.set(null); this.statusLoading.set(false);
          this.connectionError.set('Não foi possível consultar a conexão com o Melhor Envio.');
          this.notify(this.connectionError());
        },
      });
  }

  protected connect(): void {
    if (this.isSaving() || this.statusLoading() || !this.secure) return;
    if (this.form.dirty) { this.notify('Salve a origem da loja antes de sair para autorizar o Melhor Envio.'); return; }
    this.connecting.set(true); this.connectionError.set('');
    this.http.post<{ authorizationUrl: string }>('/api/integrations/melhor-envio/connect', {}, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: ({ authorizationUrl }) => {
          let url: URL;
          try { url = new URL(authorizationUrl); }
          catch {
            this.connecting.set(false); this.connectionError.set('O servidor retornou um endereço de autorização inválido.');
            this.notify(this.connectionError()); return;
          }
          if (url.origin !== 'https://sandbox.melhorenvio.com.br' || url.pathname !== '/oauth/authorize') {
            this.connecting.set(false); this.connectionError.set('O servidor retornou um endereço de autorização inválido.');
            this.notify(this.connectionError()); return;
          }
          this.connecting.set(false);
          this.document.location.assign(url.href);
        },
        error: (error: unknown) => {
          this.connecting.set(false);
          const configuration = error instanceof HttpErrorResponse && error.error?.code === 'MELHOR_ENVIO_CONFIGURATION';
          this.connectionError.set(configuration
            ? 'A configuração do Melhor Envio está incompleta ou o domínio de retorno não corresponde a este endereço. Confira o .env e reinicie o backend.'
            : 'Não foi possível iniciar a autorização. Consulte o estado da conexão antes de tentar novamente.');
          this.notify(this.connectionError());
        },
      });
  }

  protected disconnect(): void {
    if (this.isSaving() || !this.confirmingDisconnect()) return;
    this.connecting.set(true);
    this.http.delete<void>('/api/integrations/melhor-envio', { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: () => {
          this.connecting.set(false); this.confirmingDisconnect.set(false);
          this.status.set('DISCONNECTED'); this.connectionError.set('');
          this.notifications.show('delivery-settings', 'Conexão removida da nossa plataforma.', 'success');
        },
        error: () => {
          this.connecting.set(false);
          this.connectionError.set('Não foi possível confirmar a desconexão. Consulte o estado antes de repetir.');
          this.notify(this.connectionError());
        },
      });
  }

  protected statusLabel(): string {
    const status = this.status();
    return status === null ? 'Estado não confirmado'
      : { DISCONNECTED: 'Não conectado', PENDING: 'Autorização pendente', CONNECTED: 'Conectado',
        REFRESHING: 'Renovando autorização', REAUTHORIZE: 'Nova autorização necessária' }[status];
  }
}
