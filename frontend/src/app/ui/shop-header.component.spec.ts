import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { CartService } from '../shop/cart.service';
import { ShopHeaderComponent } from './shop-header.component';

describe('store header navigation', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [
    provideRouter([{ path: 'entrar', children: [] }, { path: 'favoritos', children: [] }]),
    { provide: AuthService, useValue: { accountKind: () => null } },
    { provide: CartService, useValue: { count: () => 0, busy: () => false } },
  ] }));
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.getElementById('colecao')?.remove(); });

  it('hides profile, favorites and bag on login and restores them on other pages', async () => {
    await TestBed.inject(Router).navigateByUrl('/entrar');
    const fixture = TestBed.createComponent(ShopHeaderComponent); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.header-actions')).toBeNull();
    expect(fixture.nativeElement.querySelector('.wordmark')).not.toBeNull();
    await TestBed.inject(Router).navigateByUrl('/favoritos'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[aria-label="Favoritos"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[title="Sacola"]')).not.toBeNull();
  });

  it.each([false, true])('scrolls locally without routing, respecting reduced motion: %s', (reduced) => {
    const collection = document.createElement('section'); collection.id = 'colecao'; document.body.append(collection);
    const scroll = vi.fn(); collection.scrollIntoView = scroll;
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: reduced })));
    const fixture = TestBed.createComponent(ShopHeaderComponent); fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('.browse a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('#colecao');
    link.click();
    expect(scroll).toHaveBeenCalledWith({ behavior: reduced ? 'instant' : 'smooth', block: 'start' });
    expect(TestBed.inject(Router).url).toBe('/');
    link.dispatchEvent(new MouseEvent('click', { ctrlKey: true }));
    expect(scroll).toHaveBeenCalledOnce();
  });
});
