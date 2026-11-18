import { DestroyRef, Directive, ElementRef, Renderer2, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

@Directive({ selector: 'a[routerLink][appKeepScroll]' })
export class KeepScrollDirective {
  constructor() {
    const link = inject(RouterLink, { self: true });
    const router = inject(Router);
    const element = inject<ElementRef<HTMLAnchorElement>>(ElementRef).nativeElement;
    // RouterLink does not expose the router's per-navigation scroll option.
    const stop = inject(Renderer2).listen(element, 'click', (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.shiftKey || event.altKey || event.metaKey ||
          (element.target && element.target !== '_self') || element.hasAttribute('download')) return;
      const url = link.urlTree;
      if (!url) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      void router.navigateByUrl(url, { scroll: 'manual' });
    }, { capture: true });
    inject(DestroyRef).onDestroy(stop);
  }
}
