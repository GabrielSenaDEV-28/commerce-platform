import { Component, DestroyRef, ElementRef, afterRenderEffect, effect, inject, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../auth/auth.service';
import { ToastStackComponent } from '../ui/toast-stack.component';
import { ToastService } from '../ui/toast.service';
import { CartService } from './cart.service';

@Component({
  selector: 'app-cart-recovery',
  imports: [ToastStackComponent],
  template: `
    <app-toast-stack />
    @if (auth.expiredSession()) {
      <dialog #expiry aria-labelledby="session-title" aria-describedby="session-description" (cancel)="$event.preventDefault()">
        <span class="eyebrow">ACESSO À CONTA</span>
        <h2 id="session-title">Sua sessão expirou</h2>
        <p id="session-description">Entre novamente para acessar sua conta e recuperar sua sacola. Confira se suas alterações recentes foram salvas.</p>
        <button type="button" autofocus [disabled]="auth.redirectingAfterExpiry()" (click)="auth.signInAgain()">Fazer login</button>
      </dialog>
    }
  `,
  styles: [`
    dialog { width:min(26rem, calc(100% - 2rem)); box-sizing:border-box; padding:1.65rem; border:1px solid #dbe5dd;
      border-radius:1rem; color:#20372b; background:#fff; box-shadow:0 1rem 3rem #17332838; }
    dialog::backdrop { background:#13251c99; }
    .eyebrow { font-size:.7rem; letter-spacing:.12em; font-weight:700; color:#387453; }
    h2 { margin:.6rem 0; }
    p { line-height:1.5; margin:0 0 1.2rem; }
    button { background:#315d42; color:#fff; border:0; border-radius:.5rem; padding:.7rem 1rem; cursor:pointer; font:inherit; }
  `],
})
export class CartRecoveryComponent {
  protected readonly auth = inject(AuthService);
  private readonly cart = inject(CartService);
  private readonly notifications = inject(ToastService);
  private readonly destroy = inject(DestroyRef);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('expiry');

  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialog()?.nativeElement;
      if (dialog && !dialog.open) dialog.showModal();
    });
    effect(() => {
      this.auth.customerIdentity();
      if (!this.auth.restorationError() && !this.auth.expiredSession()) untracked(() => this.retry());
    });
    effect(() => {
      const error = this.auth.restorationError();
      if (error) this.notifications.show('session-recovery', error, 'error', [{
        label: 'Tentar recuperar sessão',
        run: () => this.auth.restore().pipe(takeUntilDestroyed(this.destroy)).subscribe(),
      }]);
      else this.notifications.dismiss('session-recovery');
    });
    effect(() => {
      const error = this.cart.recoveryError();
      if (error && !this.auth.restorationError() && !this.auth.expiredSession()) {
        const actions = [{ label: 'Tentar recuperar sacola', run: () => this.retry() }];
        if (this.cart.canDiscardAnonymous()) actions.push({
          label: 'Descartar acesso anônimo expirado',
          run: () => { if (!this.cart.busy()) this.recover(this.cart.forgetExpiredAnonymous()); },
        });
        this.notifications.show('cart-recovery', error, 'error', actions);
      } else this.notifications.dismiss('cart-recovery');
    });
    effect(() => {
      const notice = this.cart.notice();
      if (notice) this.notifications.show('cart-storage', notice, 'info');
      else this.notifications.dismiss('cart-storage');
    });
  }

  private retry(): void {
    if (!this.cart.busy()) this.recover(this.cart.load());
  }

  private recover(request: ReturnType<CartService['load']>): void {
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({ error: () => { /* Reported through recoveryError. */ } });
  }
}
