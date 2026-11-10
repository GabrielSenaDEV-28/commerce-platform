import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const kind = auth.accountKind();
  const token = kind === 'merchant' ? auth.merchantAccessToken() : auth.customerAccessToken();
  const authenticated = request.url.startsWith('/api/') && token && request.headers.get('Authorization') === `Bearer ${token}`;
  return next(request).pipe(catchError((error: unknown) => {
    if (authenticated && kind && token && error instanceof HttpErrorResponse && error.status === 401) auth.expire(kind, token);
    return throwError(() => error);
  }));
};
