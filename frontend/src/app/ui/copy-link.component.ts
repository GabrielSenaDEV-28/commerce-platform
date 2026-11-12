import { DOCUMENT } from '@angular/common';
import { Component, Input, inject, signal } from '@angular/core';
import { UI } from './ui';

@Component({
  selector: 'app-copy-link',
  imports: [...UI],
  host: { '[class.compact]': 'iconOnly' },
  template: `
    <button type="button" [class.icon-action]="iconOnly" [attr.aria-label]="iconOnly ? label : null" [disabled]="copying()" (click)="copy()">
      @if (iconOnly) {
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7 .1l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7-.1l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></svg>
        <span class="copy-tooltip" aria-hidden="true">{{ label }}</span>
      } @else { {{ copying() ? 'Copiando...' : label }} }
    </button>
    <span role="status">{{ message() }}</span>
    @if (failed()) {
      <label>Copie o endereço manualmente
        <input readonly [value]="address()" (focus)="select($event)" />
      </label>
    }`,
  styles: [`
    :host { display:block; margin:10px 0; overflow-wrap:anywhere; }
    span { display:inline-block; margin:6px 10px; font-size:.8rem; color:#315d42; }
    label { display:block; font-size:.8rem; margin-top:8px; } input { display:block; width:100%; margin-top:8px; }
    :host.compact { margin:0; position:relative; }
    :host.compact > span:empty { display:none; }
    :host.compact > span:not(:empty) { display:block; margin:4px 0; max-width:180px; }
    .icon-action { position:relative; display:inline-grid; place-items:center; width:28px; height:28px; padding:0; background:transparent; border:0; color:#52705e; border-radius:50%; vertical-align:middle; }
    .copy-tooltip { display:none; position:absolute; bottom:calc(100% + 6px); right:0; width:max-content; max-width:220px; margin:0; padding:7px 10px; border-radius:5px; background:#294b40; color:white; font-size:.73rem; pointer-events:none; }
    .icon-action:hover .copy-tooltip, .icon-action:focus-visible .copy-tooltip { display:block; }
    @media(pointer:coarse) { .icon-action { width:44px; height:44px; } }
  `],
})
export class CopyLinkComponent {
  @Input({ required: true }) path = '/';
  @Input() label = 'Copiar link';
  @Input() iconOnly = false;
  private readonly document = inject(DOCUMENT);
  protected readonly copying = signal(false);
  protected readonly failed = signal(false);
  protected readonly message = signal('');
  protected readonly address = signal('');
  protected select(event: Event): void {
    if (event.target instanceof HTMLInputElement) event.target.select();
  }
  protected async copy(): Promise<void> {
    if (this.copying()) return;
    this.copying.set(true); this.failed.set(false); this.message.set('');
    const url = new URL(this.path, this.document.location.origin);
    this.address.set(url.href);
    try {
      const clipboard = this.document.defaultView?.navigator.clipboard;
      if (!clipboard) throw new Error('Clipboard unavailable');
      await clipboard.writeText(url.href);
      this.message.set('Link copiado!');
    } catch {
      this.failed.set(true);
      this.message.set('Não foi possível copiar automaticamente. Use o campo abaixo.');
    } finally { this.copying.set(false); }
  }
}
