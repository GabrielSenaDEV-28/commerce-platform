import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AccountKind, AuthService, safeReturnUrl } from './auth.service';

export function requireAccount(kind: AccountKind): CanActivateFn {
  return (_route, state) => {
    const currentKind = inject(AuthService).accountKind();
    if (currentKind === kind) return true;
    if (currentKind) return inject(Router).parseUrl(currentKind === 'merchant' ? '/painel/perfil' : '/conta/perfil');
    return inject(Router).createUrlTree(['/entrar'], {
      queryParams: { returnUrl: safeReturnUrl(state.url, kind) },
    });
  };
}
