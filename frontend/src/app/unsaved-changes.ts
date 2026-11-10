import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { AuthService } from './auth/auth.service';

export interface EditablePage {
  hasUnsavedChanges(): boolean;
  isSaving(): boolean;
  hasUnconfirmedOperation?(): boolean;
}

export function watchUnsavedChanges(page: EditablePage): void {
  const window = inject(DOCUMENT).defaultView;
  const listener = (event: BeforeUnloadEvent) => {
    if (page.hasUnsavedChanges() || page.isSaving() || page.hasUnconfirmedOperation?.()) { event.preventDefault(); event.returnValue = ''; }
  };
  window?.addEventListener('beforeunload', listener);
  inject(DestroyRef).onDestroy(() => window?.removeEventListener('beforeunload', listener));
}

export function confirmDiscard(window: Window | null): boolean {
  return window?.confirm('Há alterações não salvas. Deseja descartá-las?') ?? false;
}

export const leaveEditablePage: CanDeactivateFn<EditablePage> = (page) => {
  const auth = inject(AuthService);
  if (!auth.accountKind() && auth.redirectingAfterExpiry()) return true;
  const window = inject(DOCUMENT).defaultView;
  if (page.isSaving()) {
    window?.alert('Aguarde a conclusão da operação antes de sair.');
    return false;
  }
  return !page.hasUnsavedChanges() || confirmDiscard(window);
};
