import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Order } from '../shop/checkout.models';
import { ToastService } from '../ui/toast.service';
import { UI } from '../ui/ui';
import { debounceTime, interval, Subscription } from 'rxjs';
import { watchUnsavedChanges } from '../unsaved-changes';

interface Summary { id: string; customerName: string; customerEmail: string; customerCpf: string | null; createdAt: string; status: string; total: number; fulfillmentType: 'DELIVERY' | 'PICKUP' }
interface Page { content: Summary[]; page: number; totalPages: number; totalElements: number }
const dateString = (date: Date) => date.toISOString().slice(0, 10);

@Component({
  selector: 'app-merchant-orders',
  imports: [...UI, ReactiveFormsModule, RouterLink, CurrencyPipe, DatePipe],
  templateUrl: './merchant-orders.component.html',
  styleUrls: ['../shop/shop.css', './merchant-orders.component.css'],
})
export class MerchantOrdersComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly notifications = inject(ToastService);
  protected readonly id = this.route.snapshot.paramMap.get('orderId');
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly page = signal<Page | null>(null);
  protected readonly order = signal<Order | null>(null);
  protected readonly confirmDelivery = signal(false);
  private listRequest?: Subscription;
  protected readonly filters = inject(FormBuilder).nonNullable.group({
    startDate: [''], endDate: [''], status: [''], cpf: [''], fulfillmentType: [''], orderId: [''],
  });

  constructor() {
    watchUnsavedChanges(this);
    this.destroy.onDestroy(() => this.listRequest?.unsubscribe());
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
    const part = (name: string) => parts.find((value) => value.type === name)!.value;
    const today = new Date(`${part('year')}-${part('month')}-${part('day')}T00:00:00Z`);
    const monday = new Date(today);
    monday.setUTCDate(today.getUTCDate() - (today.getUTCDay() + 6) % 7);
    const sunday = new Date(monday); sunday.setUTCDate(monday.getUTCDate() + 6);
    const params = this.route.snapshot.queryParamMap;
    this.filters.reset({
      startDate: params.get('startDate') ?? dateString(monday), endDate: params.get('endDate') ?? dateString(sunday),
      status: params.get('status') ?? '', cpf: params.get('cpf') ?? '',
      fulfillmentType: params.get('fulfillmentType') ?? '', orderId: params.get('orderId') ?? '',
    });
    if (this.id) this.loadDetails(); else this.load(Number(params.get('page') ?? 0));
    if (!this.id) {
      for (const name of ['startDate', 'endDate', 'status', 'fulfillmentType'] as const) {
        this.filters.controls[name].valueChanges.pipe(takeUntilDestroyed(this.destroy)).subscribe(() => this.load(0));
      }
      for (const name of ['cpf', 'orderId'] as const) {
        this.filters.controls[name].valueChanges.pipe(debounceTime(350), takeUntilDestroyed(this.destroy)).subscribe(() => this.load(0));
      }
    } else {
      interval(20_000).pipe(takeUntilDestroyed(this.destroy)).subscribe(() => {
        if (!this.loading() && !this.busy() && !this.confirmDelivery() && !this.error()) this.loadDetails();
      });
    }
  }
  isSaving(): boolean { return this.busy(); }
  hasUnsavedChanges(): boolean { return false; }
  private headers(): HttpHeaders { return new HttpHeaders({ Authorization: `Bearer ${this.auth.merchantAccessToken()}` }); }
  protected status(value: string): string {
    return ({ PENDING: 'Aguardando pagamento', PAID: 'Pago', DELIVERED: 'Entregue', CANCELLED: 'Cancelado',
      EXPIRED: 'Cancelado', PAYMENT_REVIEW: 'Pagamento em revisão' } as Record<string, string>)[value] ?? value;
  }
  protected applied = signal<Record<string, string | number>>({});
  protected openOrder(event: MouseEvent, id: string): void {
    if (event.target instanceof Element && event.target.closest('a')) return;
    void this.router.navigate(['/painel/pedidos', id], { queryParamsHandling: 'preserve' });
  }
  protected clear(): void {
    this.filters.reset({ startDate: '', endDate: '', status: '', cpf: '', fulfillmentType: '', orderId: '' }, { emitEvent: false }); this.load(0);
  }
  protected load(page = 0): void {
    if (this.busy()) return;
    this.listRequest?.unsubscribe();
    this.loading.set(false);
    const value = this.filters.getRawValue();
    if ((value.startDate && value.endDate && value.startDate > value.endDate) ||
        (value.cpf && !/^\d{11}$/.test(value.cpf.replace(/[.-]/g, ''))) ||
        (value.orderId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.orderId))) {
      this.page.set(null); this.error.set('Confira o período, o CPF com 11 números e o número completo do pedido.'); return;
    }
    const params: Record<string, string | number> = { ...value, cpf: value.cpf.replace(/[.-]/g, ''), page, size: 50 };
    Object.keys(params).forEach((key) => { if (params[key] === '') delete params[key]; });
    this.loading.set(true); this.error.set('');
    this.listRequest = this.http.get<Page>('/api/orders', { headers: this.headers(), params }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (result) => {
        this.page.set(result); this.loading.set(false); this.applied.set(params);
        // CPF is intentionally not persisted in the URL/browser history.
        const { cpf: _cpf, size: _size, ...query } = params;
        void this.router.navigate([], { relativeTo: this.route, queryParams: { ...query, startDate: value.startDate, endDate: value.endDate }, replaceUrl: true, scroll: 'manual' });
      },
      error: () => { this.loading.set(false); this.report('Não foi possível carregar os pedidos. Tente novamente.'); },
    });
  }
  protected paginate(page: number): void {
    const applied = this.applied();
    this.filters.reset({
      startDate: String(applied['startDate'] ?? ''), endDate: String(applied['endDate'] ?? ''),
      status: String(applied['status'] ?? ''), cpf: String(applied['cpf'] ?? ''),
      fulfillmentType: String(applied['fulfillmentType'] ?? ''), orderId: String(applied['orderId'] ?? ''),
    }, { emitEvent: false });
    this.load(page);
  }
  protected loadDetails(): void {
    if (this.loading() || this.busy()) return;
    this.loading.set(true);
    this.error.set('');
    this.http.get<Order>(`/api/orders/${encodeURIComponent(this.id ?? '')}`, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (order) => { this.order.set(order); this.loading.set(false); if (order.status !== 'PAID') this.confirmDelivery.set(false); },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 404) this.order.set(null);
          this.loading.set(false);
          this.report(error instanceof HttpErrorResponse && error.status === 404 ? 'Pedido não encontrado nesta loja.' : 'Não foi possível consultar o pedido.');
        },
      });
  }
  protected deliver(): void {
    if (this.loading() || this.busy() || !!this.error() || !this.confirmDelivery() || this.order()?.status !== 'PAID' || this.order()?.fulfillmentType !== 'PICKUP') return;
    this.busy.set(true); this.error.set('');
    this.http.post<Order>(`/api/orders/${encodeURIComponent(this.id ?? '')}/complete-pickup`, {}, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (order) => { this.order.set(order); this.busy.set(false); this.confirmDelivery.set(false); this.notifications.show('merchant-order', 'Retirada confirmada. Pedido entregue.', 'success'); },
        error: () => { this.busy.set(false); this.confirmDelivery.set(false); this.report('Não foi possível confirmar a entrega. Consulte novamente para conferir o resultado antes de repetir.'); },
      });
  }
  private report(message: string): void { this.error.set(message); this.notifications.show('merchant-order', message); }
}
