import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { CheckoutExpiredDialogComponent } from './checkout-expired-dialog.component';

describe('checkout expiry dialog', () => {
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true, value: vi.fn(function(this: HTMLDialogElement) { this.open = true; }),
    });
  });
  afterEach(() => vi.restoreAllMocks());
  it('shows a modal only at expiry and waits for explicit acknowledgment', async () => {
    const fixture = TestBed.createComponent(CheckoutExpiredDialogComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
    const confirmed = vi.fn(); fixture.componentInstance.confirmed.subscribe(confirmed);
    fixture.componentRef.setInput('open', true); fixture.detectChanges();
    await fixture.whenStable();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    const cancel = new Event('cancel', { cancelable: true }); dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true); expect(confirmed).not.toHaveBeenCalled();
    dialog.querySelector('button')!.click();
    expect(confirmed).toHaveBeenCalledOnce();
  });
});
