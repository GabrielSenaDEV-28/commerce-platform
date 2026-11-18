import { DestroyRef, Injectable, inject, signal } from '@angular/core';

interface ToastAction {
  label: string;
  run: () => void;
}

interface Toast {
  key: string;
  text: string;
  tone: 'error' | 'info' | 'success';
  actions: ToastAction[];
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly entries = signal<Toast[]>([]);
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  readonly toasts = this.entries.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.timers.forEach((timer) => clearTimeout(timer)));
  }

  show(key: string, text: string, tone: Toast['tone'] = 'error', actions: ToastAction[] = []): void {
    clearTimeout(this.timers.get(key));
    this.timers.set(key, setTimeout(() => this.dismiss(key), 6000));
    this.entries.update((items) => {
      const toast = { key, text, tone, actions };
      return items.some((item) => item.key === key)
        ? items.map((item) => item.key === key ? toast : item)
        : [...items, toast];
    });
  }

  dismiss(key: string): void {
    clearTimeout(this.timers.get(key));
    this.timers.delete(key);
    this.entries.update((items) => items.filter((item) => item.key !== key));
  }
}
