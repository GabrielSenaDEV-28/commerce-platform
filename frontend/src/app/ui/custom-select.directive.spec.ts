import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { CustomSelectDirective } from './custom-select.directive';

@Component({
  imports: [CustomSelectDirective, ReactiveFormsModule],
  template: `<label for="state-test">Estado</label><select id="state-test" [formControl]="control">
    <option value="">Selecione</option><option value="SP">São Paulo</option><option value="RJ">Rio de Janeiro</option>
    <option value="XX" disabled>Indisponível</option>
    @if (extra) { <option value="MG">Minas Gerais</option> }
  </select>`,
})
class SelectFixture {
  readonly control = new FormControl('');
  extra = false;
}

describe('shared custom select', () => {
  it('keeps reactive forms intact, presents a custom list and supports keyboard and touched state', () => {
    const fixture = TestBed.createComponent(SelectFixture); fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLButtonElement;
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    expect(select.classList.contains('ui-select-native')).toBe(true);
    expect(button.textContent).toBe('Selecione');
    expect(button.getAttribute('aria-labelledby')).toBe(fixture.nativeElement.querySelector('label').id);
    button.click();
    const list = document.getElementById(button.getAttribute('aria-controls')!)!;
    expect(list.hidden).toBe(false);
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(fixture.componentInstance.control.value).toBe('SP');
    expect(fixture.componentInstance.control.touched).toBe(true);
    expect(button.textContent).toBe('São Paulo');
    expect(list.hidden).toBe(true);
    fixture.componentInstance.control.setValue('RJ'); fixture.detectChanges();
    expect(button.textContent).toBe('Rio de Janeiro');
    button.click(); button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(list.hidden).toBe(true);
    expect(fixture.componentInstance.control.value).toBe('RJ');
    fixture.componentInstance.control.disable(); fixture.detectChanges();
    expect(button.disabled).toBe(true);
    fixture.destroy(); expect(document.getElementById(list.id)).toBeNull();
  });
  it('selects by pointer, excludes disabled options and updates projected options', () => {
    const fixture = TestBed.createComponent(SelectFixture); fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLButtonElement;
    button.click();
    const list = document.getElementById(button.getAttribute('aria-controls')!)!;
    (list.querySelector('[data-index="3"]') as HTMLElement).click();
    expect(fixture.componentInstance.control.value).toBe('');
    (list.querySelector('[data-index="2"]') as HTMLElement).click();
    expect(fixture.componentInstance.control.value).toBe('RJ');
    fixture.componentInstance.extra = true; fixture.detectChanges();
    button.click();
    expect(list.textContent).toContain('Minas Gerais');
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'm', bubbles: true }));
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(fixture.componentInstance.control.value).toBe('MG');
  });
});
