import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { App } from './app';
import { provideHttpClient } from '@angular/common/http';

describe('skip navigation', () => {
  it('moves focus into the routed main content without changing the route fragment', () => {
    const focus = vi.fn(); const scrollIntoView = vi.fn();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient()] });
    TestBed.overrideComponent(App, { add: { providers: [{ provide: DOCUMENT, useValue: {
      getElementById: (id: string) => id === 'main-content' ? { focus, scrollIntoView } : null,
    } }] } });
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    fixture.nativeElement.querySelector('.skip-link').dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    expect(focus).toHaveBeenCalledOnce();
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' });
  });
});
