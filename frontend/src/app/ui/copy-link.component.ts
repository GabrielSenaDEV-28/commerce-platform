import { DOCUMENT } from '@angular/common';
import { Component, Input, inject, signal } from '@angular/core';
import { UI } from './ui';

@Component({
  selector: 'app-copy-link',
  imports: [...UI],
  template: `
    <button type="button" [disabled]="copying()" (click)="copy()">{{ copying() ? 'Copiando...' : label }}</button>
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
  `],
})
export class CopyLinkComponent {
  @Input({ required: true }) path = '/';
  @Input() label = 'Copiar link';
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
