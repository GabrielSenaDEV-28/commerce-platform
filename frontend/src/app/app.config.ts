import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth/auth.service';
import { DOCUMENT } from '@angular/common';
import { provideRouter, RouteReuseStrategy } from '@angular/router';
import { EditableRouteReuseStrategy } from './editable-route-reuse.strategy';
import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { sessionInterceptor } from './auth/session.interceptor';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

registerLocaleData(localePt);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([sessionInterceptor])),
    provideAppInitializer(() => ['localhost', '127.0.0.1'].includes(inject(DOCUMENT).location.hostname)
      ? undefined : firstValueFrom(inject(AuthService).restore())),
    provideRouter(routes),
    { provide: RouteReuseStrategy, useClass: EditableRouteReuseStrategy },
  ]
};
