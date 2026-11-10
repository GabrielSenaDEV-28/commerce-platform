import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup } from '@angular/forms';
import { UI } from './ui';
import { applyServerFieldErrors } from './validation';

@Component({
  imports: [...UI],
  template: `
    <main>Conteúdo principal</main>
    <button type="button" disabled>Indisponível</button>
    <app-info text="Orientação do campo" />
    <app-record-card link="/registro/1" label="Abrir registro">
      <strong>Registro</strong><button recordActions type="button" (click)="actions = actions + 1">Editar</button>
    </app-record-card>
    <app-record-card [staticCard]="true" label="Somente leitura"><p>Dados do registro</p></app-record-card>
    <span class="field-error">Campo obrigatório</span>`,
})
class UiExample { actions = 0; }

describe('shared UI', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  it('uses native button semantics and a single keyboard-accessible tooltip without title', () => {
    const fixture = TestBed.createComponent(UiExample); fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector<HTMLButtonElement>('.ui-button')!.disabled).toBe(true);
    expect(element.querySelector('[title]')).toBeNull();
    expect(element.querySelectorAll('.ui-tooltip').length).toBe(1);
    expect(element.querySelector('.ui-info')!.getAttribute('tabindex')).toBe('0');
    expect(element.querySelector('.field-error')!.getAttribute('role')).toBe('alert');
    expect(element.querySelector('main')!.id).toBe('main-content');
    expect(element.querySelector('main')!.getAttribute('tabindex')).toBe('-1');
  });

  it('keeps card actions separate from the full-card link and does not invent actions on static records', () => {
    const fixture = TestBed.createComponent(UiExample); fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const action = element.querySelector<HTMLButtonElement>('[recordActions]')!;
    expect(action.closest('a')).toBeNull();
    expect(element.querySelector('.ui-record-main')!.getAttribute('href')).toBe('/registro/1');
    action.click();
    expect(fixture.componentInstance.actions).toBe(1);
    expect(TestBed.inject(Router).url).toBe('/');
    const staticCard = element.querySelectorAll('app-record-card')[1];
    expect(staticCard.querySelector('button, a')).toBeNull();
  });

  it('maps server validation to the matching field without hiding unknown errors', () => {
    const form = new FormGroup({ name: new FormControl('Nome') });
    expect(applyServerFieldErrors(form, new HttpErrorResponse({ status: 400, error: { errors: ['name: inválido'] } }))).toBe(true);
    expect(form.controls.name.touched).toBe(true);
    expect(form.controls.name.hasError('server')).toBe(true);
    expect(applyServerFieldErrors(form, new HttpErrorResponse({ status: 400, error: { errors: ['unknown: inválido'] } }))).toBe(false);
    expect(applyServerFieldErrors(form, new HttpErrorResponse({ status: 503 }))).toBe(false);
  });
});
