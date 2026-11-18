import { Component, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { CartRecoveryComponent } from './shop/cart-recovery.component';
import { ShopHeaderComponent } from './ui/shop-header.component';

@Component({
  imports: [RouterOutlet, CartRecoveryComponent, ShopHeaderComponent],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly document = inject(DOCUMENT);
  protected readonly storeHost = !!this.document.location?.hostname && !['localhost', '127.0.0.1'].includes(this.document.location.hostname);
  protected skipToContent(event: Event): void {
    event.preventDefault();
    const main = this.document.getElementById('main-content');
    main?.focus(); main?.scrollIntoView({ block: 'start' });
  }
}
