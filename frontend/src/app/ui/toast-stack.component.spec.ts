import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ToastService } from './toast.service';
import { ToastStackComponent } from './toast-stack.component';

describe('toast stack', () => {
  afterEach(() => vi.useRealTimers());

  it('stacks messages and closes each six seconds after it appears', () => {
    vi.useFakeTimers();
    const notifications = TestBed.inject(ToastService);
    notifications.show('saved', 'Salvo', 'success');
    vi.advanceTimersByTime(1000);
    notifications.show('error', 'Erro');
    vi.advanceTimersByTime(4999);
    expect(notifications.toasts().map(({ key }) => key)).toEqual(['saved', 'error']);
    vi.advanceTimersByTime(1);
    expect(notifications.toasts().map(({ key }) => key)).toEqual(['error']);
    vi.advanceTimersByTime(1000);
    expect(notifications.toasts()).toEqual([]);
  });

  it('dismisses by dragging right but not left', () => {
    const notifications = TestBed.inject(ToastService);
    const fixture = TestBed.createComponent(ToastStackComponent);
    notifications.show('saved', 'Salvo', 'success');
    fixture.detectChanges();
    const toast = fixture.nativeElement.querySelector('.toast.success');
    const dispatch = (type: string, clientX: number) => toast.dispatchEvent(
      Object.assign(new MouseEvent(type, { clientX, bubbles: true }), { pointerId: 1 }),
    );
    dispatch('pointerdown', 200);
    dispatch('pointerup', 50);
    expect(notifications.toasts()).toHaveLength(1);
    dispatch('pointerdown', 200);
    dispatch('pointermove', 325);
    fixture.detectChanges();
    expect(toast.style.translate).toBe('125px');
    dispatch('pointerup', 325);
    expect(notifications.toasts()).toEqual([]);
  });
});
