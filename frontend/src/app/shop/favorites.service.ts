import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class FavoritesService {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly key = 'commerce.favorites';
  private readonly products = signal<ReadonlySet<string>>(new Set());
  readonly ids = signal<readonly string[]>([]);
  readonly error = signal('');
  constructor() {
    this.restore();
    const changed = (event: StorageEvent) => {
      if (event.key === this.key || event.key === null) this.restore();
    };
    this.window?.addEventListener('storage', changed);
    inject(DestroyRef).onDestroy(() => this.window?.removeEventListener('storage', changed));
  }
  private restore(): void {
    try {
      const raw = this.window?.localStorage.getItem(this.key);
      const ids: unknown = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(ids) || !ids.every((id): id is string => typeof id === 'string' && id.length > 0 && id.length <= 100)) {
        throw new Error('Invalid favorites');
      }
      this.set(ids); this.error.set('');
    } catch {
      this.error.set('Não foi possível recuperar os favoritos deste navegador. Você pode tentar novamente ou limpar os dados locais.');
    }
  }
  private set(ids: readonly string[]): void {
    const unique = new Set(ids);
    this.products.set(unique); this.ids.set([...unique]);
  }
  retry(): void { this.restore(); }
  clear(): void { this.save([]); }
  has(id: string): boolean { return this.products().has(id); }
  toggle(id: string): void {
    const next = new Set(this.products());
    if (next.has(id)) next.delete(id); else next.add(id);
    this.save([...next]);
  }
  private save(ids: readonly string[]): void {
    this.set(ids);
    try {
      if (!this.window) throw new Error('Storage unavailable');
      this.window.localStorage.setItem(this.key, JSON.stringify(ids)); this.error.set('');
    } catch {
      this.error.set('Os favoritos foram alterados apenas nesta aba. O navegador não permitiu salvar para a próxima visita.');
    }
  }
}
