import { Component, ElementRef, afterRenderEffect, input, output, viewChild } from '@angular/core';
import { UI } from './ui';

@Component({
  selector: 'app-checkout-expired-dialog',
  imports: [...UI],
  template: `@if (open()) { <dialog #dialog (cancel)="$event.preventDefault()"><span class="overline">PRAZO ENCERRADO</span>
    <h2>O tempo para concluir sua compra terminou.</h2>
    <p>A reserva foi encerrada e os itens foram liberados. Se havia um pedido aguardando pagamento, ele foi cancelado.</p>
    <p>Sua conta não foi apagada. Você pode explorar a loja e iniciar uma nova compra.</p>
    <button type="button" class="primary" (click)="confirmed.emit()">Entendi, voltar à loja</button></dialog> }`,
  styles: [`dialog { max-width:460px; width:calc(100% - 48px); box-sizing:border-box; border:1px solid #dfe7df; border-radius:16px; padding:32px; color:#294b40; box-shadow:0 24px 80px #183b3433; }
    dialog::backdrop { background:#163d3566; backdrop-filter:blur(3px); }
    h2 { font:400 1.8rem Georgia,serif; } p { font-size:.9rem; line-height:1.7; color:#52675c; }
    .overline { font-size:.7rem; letter-spacing:.12em; } button { width:100%; margin-top:16px; }`],
})
export class CheckoutExpiredDialogComponent {
  readonly open = input(false);
  readonly confirmed = output<void>();
  private readonly dialog = viewChild('dialog', { read: ElementRef<HTMLDialogElement> });
  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialog()?.nativeElement;
      if (dialog && this.open() && !dialog.open) dialog.showModal();
      else if (dialog && !this.open() && dialog.open) dialog.close();
    });
  }
}
