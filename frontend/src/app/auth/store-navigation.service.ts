import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class StoreNavigation {
  private readonly document = inject(DOCUMENT);
  address(subdomain: string): string | null {
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(subdomain)) return null;
    const location = this.document.location;
    if (!['localhost', '127.0.0.1'].includes(location.hostname) && !location.hostname.endsWith('.localhost')) return null;
    return `${location.protocol}//${subdomain}.localhost${location.port ? ':' + location.port : ''}`;
  }
  open(url: string): void { this.document.location.assign(url); }
}
