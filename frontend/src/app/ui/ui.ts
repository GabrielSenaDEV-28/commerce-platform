import { Component, Directive, EventEmitter, Input, Output } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'button',
  template: '<ng-content />',
  host: { class: 'ui-button' },
})
export class UiButtonComponent {}

@Component({
  selector: 'app-info',
  template: '<span class="ui-info" tabindex="0" role="note" [attr.aria-label]="text">ⓘ<span class="ui-tooltip" aria-hidden="true">{{ text }}</span></span>',
  styles: [`
    :host { display:inline-flex; vertical-align:middle; }
    .ui-info { position:relative; cursor:help; color:#52705e; font-size:.9rem; }
    .ui-tooltip { display:none; position:absolute; z-index:20; bottom:calc(100% + 8px); left:0; width:min(240px,65vw); padding:10px 12px; border-radius:6px; background:#294b40; color:white; font:400 .78rem/1.5 system-ui,sans-serif; text-transform:none; letter-spacing:normal; box-shadow:0 6px 20px #294b4020; }
    .ui-info:hover .ui-tooltip, .ui-info:focus .ui-tooltip { display:block; }
  `],
})
export class InfoComponent { @Input({ required: true }) text = ''; }

@Component({
  selector: 'p.field-error, span.field-error',
  template: '<ng-content />',
  host: { role: 'alert', class: 'ui-field-error' },
  styles: [':host { display:block; margin:4px 0 0; padding:6px 8px; border-radius:4px; background:#fbefed; color:#8c342d; font-size:.8rem; line-height:1.5; }'],
})
export class FieldErrorComponent {}

@Directive({ selector: 'input, textarea, select', host: { class: 'ui-input' } })
export class UiInputDirective {}

@Directive({ selector: 'a', host: { class: 'ui-link' } })
export class UiLinkDirective {}

@Directive({ selector: 'main', host: { id: 'main-content', tabindex: '-1' } })
export class MainContentDirective {}

@Component({
  selector: 'div.message, p.error',
  template: '<ng-content />',
  host: { class: 'ui-notice', role: 'status' },
  styles: [':host { display:block; padding:14px 16px; border-radius:6px; line-height:1.6; font-size:.83rem; }'],
})
export class NoticeComponent {}

@Component({
  selector: 'dialog',
  template: '<ng-content />',
  styles: [`
    :host { width:min(600px,calc(100% - 32px)); max-height:85vh; overflow-y:auto; border:1px solid #dfe7df; border-radius:12px; padding:28px; color:#294b40; box-shadow:0 20px 70px #16312635; }
    :host::backdrop { background:#16312680; backdrop-filter:blur(3px); }
  `],
})
export class UiDialogComponent {}

@Component({
  selector: 'app-record-card',
  imports: [RouterLink, UiButtonComponent],
  template: `
    <article class="ui-record" [class.interactive]="!staticCard">
      @if (link) {
        <a class="ui-record-main" [routerLink]="link" [attr.aria-label]="label"></a>
      }
      <div class="ui-record-content"><ng-content /></div>
      @if (!link && !staticCard) { <button type="button" class="ui-record-overlay" [disabled]="disabled" [attr.aria-label]="label" (click)="selectRecord.emit()"></button> }
      <div class="ui-record-actions"><ng-content select="[recordActions]" /></div>
    </article>`,
  styles: [`
    :host { display:block; margin:10px 0; }
    .ui-record { position:relative; display:flex; align-items:center; justify-content:space-between; gap:20px; padding:18px 20px; border:1px solid #e0e8df; border-radius:8px; background:white; transition:background .18s,border-color .18s,box-shadow .18s; }
    .ui-record.interactive:hover, .ui-record:focus-within { background:#f4f8f2; border-color:#a9c2ad; box-shadow:0 5px 16px #294b400d; }
    .ui-record-main, .ui-record-overlay { position:absolute; inset:0; border:0; border-radius:8px; background:transparent; z-index:1; padding:0; }
    .ui-record-content { flex:1; min-width:0; color:#294b40; overflow-wrap:anywhere; }
    .ui-record-actions { position:relative; z-index:2; flex-shrink:0; }
    @media(max-width:720px) { .ui-record { flex-wrap:wrap; } }
  `],
})
export class RecordCardComponent {
  @Input() link: string | string[] | null = null;
  @Input({ required: true }) label = '';
  @Input() disabled = false;
  @Input() staticCard = false;
  @Output() selectRecord = new EventEmitter<void>();
}

export const UI = [UiButtonComponent, InfoComponent, FieldErrorComponent, RecordCardComponent, UiInputDirective, UiLinkDirective, NoticeComponent, UiDialogComponent, MainContentDirective] as const;
