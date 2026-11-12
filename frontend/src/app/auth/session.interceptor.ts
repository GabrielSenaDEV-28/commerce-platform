import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const kind = auth.accountKind();
  const version = auth.sessionVersion();
  const token = kind === 'merchant' ? auth.merchantAccessToken() : auth.customerAccessToken();
  const authenticated = request.url.startsWith('/api/') && token && request.headers.get('Authorization') === `Bearer ${token}`;
  return next(request).pipe(catchError((error: unknown) => {
    if (authenticated && kind && token && error instanceof HttpErrorResponse && error.status === 401 && version === auth.sessionVersion()) {
      if (!auth.usesBrowserSession()) auth.expire(kind, token);
      else {
        const retry = () => {
          const renewed = kind === 'merchant' ? auth.merchantAccessToken() : auth.customerAccessToken();
          if (!renewed || version !== auth.sessionVersion()) return throwError(() => error);
          return next(request.clone({ setHeaders: { Authorization: `Bearer ${renewed}` } })).pipe(
            catchError((failure: unknown) => {
              if (failure instanceof HttpErrorResponse && failure.status === 401) auth.expire(kind, renewed);
              return throwError(() => failure);
            }),
          );
        };
        const current = kind === 'merchant' ? auth.merchantAccessToken() : auth.customerAccessToken();
        return current !== token ? retry() : auth.restore().pipe(switchMap((restored) => restored ? retry() : throwError(() => error)));
      }
    }
    return throwError(() => error);
  }));
};
