import { Component, DestroyRef, effect, inject, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { UI } from '../ui/ui';
import { AuthService } from '../auth/auth.service';
import { CartService } from './cart.service';

@Component({
  selector: 'app-cart-recovery',
  imports: [...UI, RouterLink],
  template: `
    @if (auth.restorationError()) {
      <section class="recovery" aria-label="Recuperação da sessão"><p class="field-error">{{ auth.restorationError() }}</p>
        <button type="button" [disabled]="auth.restoring()" (click)="restoreSession()">Tentar recuperar sessão</button>
      </section>
    }
    @if (cart.recoveryError()) {
      <section class="recovery" aria-label="Recuperação da sacola"><p class="field-error">{{ cart.recoveryError() }}</p>
        <div class="actions"><button type="button" [disabled]="cart.busy()" (click)="retry()">Tentar recuperar sacola</button><a routerLink="/carrinho">Conferir sacola</a>
        @if (cart.canDiscardAnonymous()) { <button type="button" [disabled]="cart.busy()" (click)="discard()">Descartar acesso anônimo expirado e continuar</button> }</div>
      </section>
    }
    @if (cart.notice()) { <p class="recovery" role="status">{{ cart.notice() }}</p> }
  `,
  styles: [`.recovery { margin:0; padding:12px 20px; background:#f7f8f4; color:#315d42; font-size:.8rem; } .actions { display:flex; flex-wrap:wrap; gap:12px; align-items:center; }`],
})
export class CartRecoveryComponent {
  protected readonly cart = inject(CartService);
  protected readonly auth = inject(AuthService);
  private readonly destroy = inject(DestroyRef);
  constructor() {
    effect(() => {
      this.auth.customerIdentity();
      if (!this.auth.restorationError()) untracked(() => this.retry());
    });
  }
  protected restoreSession(): void { this.auth.restore().pipe(takeUntilDestroyed(this.destroy)).subscribe(); }
  protected retry(): void {
    this.cart.load().pipe(takeUntilDestroyed(this.destroy)).subscribe({ error: () => { /* CartService exposes the recovery error above. */ } });
  }
  protected discard(): void {
    this.cart.forgetExpiredAnonymous().pipe(takeUntilDestroyed(this.destroy)).subscribe({ error: () => { /* CartService exposes the recovery error above. */ } });
  }
}
