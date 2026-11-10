import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ProductGalleryComponent } from './product-gallery.component';

describe('accessible product gallery', () => {
  function setup() {
    const fixture = TestBed.createComponent(ProductGalleryComponent);
    fixture.componentRef.setInput('photos', ['https://example.com/1.jpg', 'https://example.com/2.jpg']);
    fixture.componentRef.setInput('name', 'Blusa');
    fixture.detectChanges();
    return fixture;
  }
  it('opens the native dialog, supports arrow keys and closes using its button', () => {
    const fixture = setup();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const open = vi.fn(() => dialog.setAttribute('open', ''));
    const close = vi.fn(() => dialog.removeAttribute('open'));
    Object.defineProperty(dialog, 'showModal', { value: open });
    Object.defineProperty(dialog, 'close', { value: close });
    fixture.nativeElement.querySelector('.expand').click();
    expect(open).toHaveBeenCalledOnce();
    expect(dialog.getAttribute('aria-labelledby')).toBe('gallery-title');
    expect(dialog.querySelector('[autofocus]')).not.toBeNull();
    const right = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    dialog.dispatchEvent(right); fixture.detectChanges();
    expect(right.defaultPrevented).toBe(true);
    expect(dialog.querySelector('img')!.getAttribute('src')).toBe('https://example.com/2.jpg');
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true })); fixture.detectChanges();
    expect(dialog.querySelector('img')!.getAttribute('src')).toBe('https://example.com/1.jpg');
    dialog.querySelector<HTMLButtonElement>('[aria-label="Fechar imagem ampliada"]')!.click();
    expect(close).toHaveBeenCalledOnce();
  });
  it('allows native Escape handling without intercepting it', () => {
    const fixture = setup();
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    fixture.nativeElement.querySelector('dialog').dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(false);
  });
  it('shows an explicit broken-image state and permits retry and navigation to other images', () => {
    const fixture = setup();
    fixture.nativeElement.querySelector('.main-photo img').dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.main-photo').textContent).toContain('Não foi possível carregar');
    expect(fixture.nativeElement.querySelector('.expand')).toBeNull();
    fixture.nativeElement.querySelector('.no-photo button').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.main-photo img')).not.toBeNull();
    fixture.nativeElement.querySelector('[aria-label="Próxima imagem"]').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.main-photo img').getAttribute('src')).toBe('https://example.com/2.jpg');
    expect(fixture.nativeElement.querySelector('[aria-label="Ver imagem 2"]').getAttribute('aria-pressed')).toBe('true');
  });
  it('resets the selection and failures when the product changes', () => {
    const fixture = setup();
    fixture.nativeElement.querySelector('[aria-label="Próxima imagem"]').click(); fixture.detectChanges();
    fixture.componentRef.setInput('photos', []); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Este produto ainda não tem fotos');
    expect(fixture.nativeElement.querySelector('.thumbnails')).toBeNull();
  });
});
