import { Component, DestroyRef, ElementRef, HostListener, Input, ViewChild, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, NavigationSkipped, Router } from '@angular/router';
import { UI } from './ui';

@Component({
  selector: 'app-mobile-menu',
  imports: [...UI],
  template: `
    <button class="menu-trigger icon-action" type="button" [disabled]="busy" [attr.aria-label]="label" (click)="open()">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
        @if (profile) { <circle cx="12" cy="8" r="3.3" /><path d="M5 20c0-3.5 2.8-5.8 7-5.8s7 2.3 7 5.8" /> }
        @else { <path d="M4 6h16M4 12h16M4 18h16" /> }
      </svg>
    </button>
    <dialog #drawer class="mobile-drawer" [class.right]="side === 'right'" [attr.aria-label]="label" (cancel)="cancel($event)" (click)="backdrop($event)">
      <div class="drawer-heading"><strong>{{ label }}</strong><button autofocus type="button" aria-label="Fechar menu" [disabled]="busy" (click)="close()">&times;</button></div>
      <div class="drawer-content"><ng-content /></div>
    </dialog>`,
  styles: [`
    :host { display:none; }
    .menu-trigger { display:grid; place-items:center; width:44px; height:44px; padding:0; border:0; background:transparent; color:#294b40; }
    dialog.mobile-drawer { position:fixed; inset:0 auto 0 0; margin:0; width:min(320px,88vw); height:100dvh; max-height:100dvh; border-radius:0 16px 16px 0; padding:24px; transform:translateX(-100%); transition:transform .26s ease, display .26s allow-discrete, overlay .26s allow-discrete; }
    dialog.mobile-drawer.right { inset:0 0 0 auto; border-radius:16px 0 0 16px; transform:translateX(100%); }
    dialog.mobile-drawer[open] { transform:translateX(0); }
    @starting-style { dialog.mobile-drawer[open] { transform:translateX(-100%); } dialog.mobile-drawer.right[open] { transform:translateX(100%); } }
    .drawer-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:28px; color:#294b40; }
    .drawer-content { display:flex; flex-direction:column; gap:8px; }
    dialog::backdrop { background:#17352d80; opacity:0; transition:opacity .26s ease, display .26s allow-discrete, overlay .26s allow-discrete; }
    dialog[open]::backdrop { opacity:1; }
    @starting-style { dialog[open]::backdrop { opacity:0; } }
    @media(prefers-reduced-motion:reduce) { dialog.mobile-drawer, dialog::backdrop { transition:none; } }
    @media(max-width:720px) { :host { display:block; } }
  `],
})
export class MobileMenuComponent {
  @Input() label = 'Menu';
  @Input() busy = false;
  @Input() side: 'left' | 'right' = 'left';
  @Input() profile = false;
  @ViewChild('drawer', { read: ElementRef }) private drawer?: ElementRef<HTMLDialogElement>;
  constructor() {
    inject(Router).events.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((event) => {
      if (event instanceof NavigationEnd || event instanceof NavigationSkipped) this.close();
    });
  }
  protected open(): void { if (!this.busy) this.drawer?.nativeElement.showModal(); }
  @HostListener('window:resize')
  protected resize(): void { if (window.innerWidth > 720) this.close(); }
  protected close(): void { if (this.drawer?.nativeElement.open) this.drawer.nativeElement.close(); }
  protected cancel(event: Event): void { if (this.busy) event.preventDefault(); }
  protected backdrop(event: MouseEvent): void {
    if (this.busy || event.target !== this.drawer?.nativeElement) return;
    const rect = this.drawer.nativeElement.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) this.close();
  }
}
