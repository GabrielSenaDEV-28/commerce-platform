import { Component, inject, signal } from '@angular/core';
import { ToastService } from './toast.service';

@Component({
  selector: 'app-toast-stack',
  template: `
    <aside class="stack" aria-label="Notificações">
      @for (toast of notifications.toasts(); track toast.key) {
        <section class="toast" [class.error]="toast.tone === 'error'" [class.success]="toast.tone === 'success'"
          [style.translate]="drag()?.key === toast.key ? drag()?.offset + 'px' : null"
          (pointerdown)="start($event, toast.key)" (pointermove)="move($event)"
          (pointerup)="finish($event)" (pointercancel)="cancel($event)" (lostpointercapture)="cancel($event)">
          <p [attr.role]="toast.tone === 'error' ? 'alert' : 'status'">{{ toast.text }}</p>
          <button type="button" class="close" aria-label="Fechar notificação" (click)="notifications.dismiss(toast.key)">×</button>
          @if (toast.actions.length) {
            <div class="actions">
              @for (action of toast.actions; track action.label) {
                <button type="button" (click)="action.run()">{{ action.label }}</button>
              }
            </div>
          }
        </section>
      }
    </aside>
  `,
  styles: [`
    .stack { position:fixed; inset:calc(var(--toast-header-offset, 0px) + .75rem) 1rem auto auto; width:min(24rem, calc(100% - 2rem)); z-index:1000;
      max-height:65dvh; overflow:auto; display:grid; gap:.6rem; pointer-events:none; }
    .toast { pointer-events:auto; position:relative; padding:.85rem 2.75rem .85rem 1rem; background:#fff; color:#20372b;
      touch-action:pan-y; user-select:none; border:1px solid #dbe5dd; border-inline-start:4px solid #387453; border-radius:.75rem; box-shadow:0 .25rem 1.25rem #17332824; }
    .toast.error { background:#fff0ee; color:#702c28; border-inline-start-color:#ac3737; }
    .toast.success { background:#e9f8ed; color:#225a36; border-inline-start-color:#387453; }
    p { margin:0; font-size:.9rem; line-height:1.45; }
    button { cursor:pointer; color:inherit; font:inherit; }
    .close { position:absolute; top:.35rem; right:.45rem; border:0; background:none; min-width:2rem; min-height:2rem; font-size:1.25rem; }
    .actions { display:flex; flex-wrap:wrap; gap:.5rem; margin-top:.6rem; }
    .actions button { background:#eff4ef; border:1px solid #dbe5dd; padding:.3rem .6rem; border-radius:.4rem; font-size:.8rem; }
  `],
})
export class ToastStackComponent {
  protected readonly notifications = inject(ToastService);
  protected readonly drag = signal<{ key: string; pointer: number; x: number; offset: number } | null>(null);

  protected start(event: PointerEvent, key: string): void {
    if (event.button !== 0 || this.drag() || !(event.target instanceof Element) || event.target.closest('button, a')) return;
    if (!(event.currentTarget instanceof HTMLElement)) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    this.drag.set({ key, pointer: event.pointerId, x: event.clientX, offset: 0 });
  }

  protected move(event: PointerEvent): void {
    const drag = this.drag();
    if (drag?.pointer === event.pointerId) this.drag.set({ ...drag, offset: Math.max(0, event.clientX - drag.x) });
  }

  protected finish(event: PointerEvent): void {
    const drag = this.drag();
    if (drag?.pointer !== event.pointerId) return;
    this.drag.set(null);
    if (event.clientX - drag.x >= 90) this.notifications.dismiss(drag.key);
  }

  protected cancel(event: PointerEvent): void {
    if (this.drag()?.pointer === event.pointerId) this.drag.set(null);
  }
}
