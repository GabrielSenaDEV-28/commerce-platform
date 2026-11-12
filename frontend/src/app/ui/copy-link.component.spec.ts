import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { CopyLinkComponent } from './copy-link.component';

describe('copy share links', () => {
  const writeText = vi.fn();
  const navigator = { clipboard: { writeText } };
  beforeEach(() => {
    writeText.mockReset(); writeText.mockResolvedValue(undefined);
    TestBed.configureTestingModule({});
    TestBed.overrideComponent(CopyLinkComponent, { add: { providers: [{ provide: DOCUMENT, useValue: {
      location: { origin: 'http://loja.localhost:4200' }, defaultView: { navigator },
    } }] } });
  });
  it('copies a clean store-origin URL without query parameters or credentials', async () => {
    const fixture = TestBed.createComponent(CopyLinkComponent);
    fixture.componentRef.setInput('path', '/produtos/p1');
    fixture.detectChanges(); fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable(); fixture.detectChanges();
    expect(writeText).toHaveBeenCalledWith('http://loja.localhost:4200/produtos/p1');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Link copiado');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
  });
  it('shows a selectable address when clipboard permission is denied instead of claiming success', async () => {
    writeText.mockRejectedValue(new Error('Permission denied'));
    const fixture = TestBed.createComponent(CopyLinkComponent);
    fixture.componentRef.setInput('path', '/entrar');
    fixture.detectChanges(); fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível copiar automaticamente');
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(input.readOnly).toBe(true);
    expect(input.value).toBe('http://loja.localhost:4200/entrar');
    input.dispatchEvent(new Event('focus'));
    expect(input.selectionEnd).toBe(input.value.length);
  });
  it('blocks duplicate copies while the browser is processing a request', async () => {
    let resolve!: () => void;
    writeText.mockImplementation(() => new Promise<void>((done) => resolve = done));
    const fixture = TestBed.createComponent(CopyLinkComponent);
    fixture.componentRef.setInput('path', '/'); fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click(); button.click(); fixture.detectChanges();
    expect(writeText).toHaveBeenCalledTimes(1); expect(button.disabled).toBe(true);
    resolve(); await fixture.whenStable(); fixture.detectChanges();
    expect(button.disabled).toBe(false);
  });
  it('provides manual copy when the browser has no clipboard API', async () => {
    TestBed.overrideComponent(CopyLinkComponent, { add: { providers: [{ provide: DOCUMENT, useValue: {
      location: { origin: 'http://loja.localhost:4200' }, defaultView: { navigator: {} },
    } }] } });
    const fixture = TestBed.createComponent(CopyLinkComponent);
    fixture.componentRef.setInput('path', '/'); fixture.detectChanges();
    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input').value).toBe('http://loja.localhost:4200/');
    expect(writeText).not.toHaveBeenCalled();
  });
  it('offers an accessible icon with one custom tooltip instead of a visible product-link button label', async () => {
    const fixture = TestBed.createComponent(CopyLinkComponent);
    fixture.componentRef.setInput('path', '/produtos/p1');
    fixture.componentRef.setInput('iconOnly', true);
    fixture.componentRef.setInput('label', 'Copiar link do produto');
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe('Copiar link do produto');
    expect(button.hasAttribute('title')).toBe(false);
    expect(button.querySelector('svg')).not.toBeNull();
    expect(button.querySelector('.copy-tooltip')!.textContent).toBe('Copiar link do produto');
    button.click(); await fixture.whenStable(); fixture.detectChanges();
    expect(writeText).toHaveBeenCalledWith('http://loja.localhost:4200/produtos/p1');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Link copiado');
  });
});
