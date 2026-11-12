import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Event as RouterEvent, NavigationCancel, NavigationCancellationCode, NavigationEnd, NavigationSkipped, NavigationSkippedCode, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { MobileMenuComponent } from './mobile-menu.component';

@Component({ imports: [MobileMenuComponent], template: '<app-mobile-menu label="Minha conta" side="right" [profile]="true" [busy]="busy"><a href="/conta/perfil">Dados pessoais</a></app-mobile-menu>' })
class Host { busy = false; }

describe('mobile menu', () => {
  let events: Subject<RouterEvent>;
  beforeEach(() => {
    events = new Subject<RouterEvent>();
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { events } }] });
  });
  function setup() {
    const fixture = TestBed.createComponent(Host); fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const dialog = root.querySelector('dialog')!;
    const show = vi.fn(() => dialog.setAttribute('open', ''));
    const close = vi.fn(() => dialog.removeAttribute('open'));
    Object.defineProperty(dialog, 'showModal', { value: show });
    Object.defineProperty(dialog, 'close', { value: close });
    return { fixture, root, dialog, show, close };
  }
  it('opens a labelled native modal, exposes its navigation and closes after navigation', () => {
    const { root, show, dialog, close } = setup();
    root.querySelector<HTMLButtonElement>('.menu-trigger')!.click();
    expect(show).toHaveBeenCalledOnce();
    expect(dialog.getAttribute('aria-label')).toBe('Minha conta');
    expect(dialog.classList.contains('right')).toBe(true);
    expect(root.querySelector('.menu-trigger circle')).not.toBeNull();
    expect(dialog.querySelector('a')?.textContent).toBe('Dados pessoais');
    events.next(new NavigationEnd(1, '/conta/perfil', '/conta/perfil'));
    expect(close).toHaveBeenCalledOnce();
  });
  it('prevents opening or dismissing the menu while a write is in progress', () => {
    const { fixture, root, dialog, show, close } = setup();
    root.querySelector<HTMLButtonElement>('.menu-trigger')!.click();
    fixture.componentInstance.busy = true; fixture.detectChanges();
    const cancel = new Event('cancel', { cancelable: true }); dialog.dispatchEvent(cancel);
    root.querySelector<HTMLButtonElement>('[aria-label="Fechar menu"]')!.click();
    expect(cancel.defaultPrevented).toBe(true); expect(close).not.toHaveBeenCalled();
    expect(root.querySelector<HTMLButtonElement>('.menu-trigger')!.disabled).toBe(true);
    expect(show).toHaveBeenCalledOnce();
  });
  it('closes for the current route too', () => {
    const { root, close } = setup();
    root.querySelector<HTMLButtonElement>('.menu-trigger')!.click();
    events.next(new NavigationSkipped(1, '/conta/perfil', 'same URL', NavigationSkippedCode.IgnoredSameUrlNavigation));
    expect(close).toHaveBeenCalledOnce();
  });
  it('keeps navigation available after a guard cancels a section change', () => {
    const { root, close } = setup();
    root.querySelector<HTMLButtonElement>('.menu-trigger')!.click();
    events.next(new NavigationCancel(1, '/conta/enderecos', 'Unsaved changes', NavigationCancellationCode.GuardRejected));
    expect(close).not.toHaveBeenCalled();
  });
  it('dismisses the modal when resizing to desktop so the page is not left inert', () => {
    const { root, close } = setup();
    root.querySelector<HTMLButtonElement>('.menu-trigger')!.click();
    window.dispatchEvent(new Event('resize'));
    expect(close).toHaveBeenCalledOnce();
  });
});
