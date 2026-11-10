import { UI } from '../ui/ui';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';

export interface VariantOption { id: string; kind: 'COLOR' | 'SIZE'; name: string; colorHex: string | null }

@Component({
  imports: [...UI],
  selector: 'app-variant-options',
  template: `
    @if (hasColor) {
      <fieldset><legend>Cor <app-info text="Escolha uma cor ou cadastre uma opção da sua loja." /></legend>
        <div class="choices color-choices" tabindex="0" aria-label="Opções de cor, role horizontalmente para ver mais">@for (option of colors; track option.name) {
          <button type="button" class="choice color-choice" [class.selected]="color.toLowerCase() === option.name.toLowerCase()" [attr.aria-pressed]="color.toLowerCase() === option.name.toLowerCase()" (click)="colorChange.emit(option.name)">
            <span class="swatch" [style.background-color]="option.colorHex"></span>{{ option.name }}
          </button>
        }</div>
        <button type="button" (click)="addingColor.set(!addingColor())">+ Outra cor</button>
        @if (addingColor()) {
          <label for="custom-color-name">Nome da cor</label><input id="custom-color-name" #colorName maxlength="80" [value]="colorDraft()" (input)="colorDraft.set(colorName.value); draftChange.emit()" />
          <label for="custom-color-hex">Amostra</label><input id="custom-color-hex" #colorHex type="color" [value]="hexDraft()" (input)="hexDraft.set(colorHex.value); draftChange.emit()" />
          <button type="button" [disabled]="busy" (click)="addOption.emit({kind:'COLOR', name:colorName.value, colorHex:colorHex.value})">Salvar cor</button>
        }
        @if (colorInvalid) { <p class="field-error" role="alert">Selecione uma cor.</p> }
      </fieldset>
    }
    @if (hasSize) {
      <fieldset><legend>Tamanho <app-info text="Escolha um tamanho ou adicione uma opção da sua loja." /></legend>
        @if (numeric) {
          <label for="numeric-size">Tamanho numérico</label><input id="numeric-size" [value]="size" #numericSize maxlength="40" (input)="sizeChange.emit(numericSize.value)" />
        } @else {
          <div class="choices">@for (option of sizes; track option.name) {
            <button type="button" class="choice" [class.selected]="size.toLowerCase() === option.name.toLowerCase()" [attr.aria-pressed]="size.toLowerCase() === option.name.toLowerCase()" (click)="sizeChange.emit(option.name)">{{ option.name }}</button>
          }</div>
          <button type="button" (click)="addingSize.set(!addingSize())">+ Outro tamanho</button>
          @if (addingSize()) {
            <label for="custom-size-name">Nome do tamanho</label><input id="custom-size-name" #sizeName maxlength="40" [value]="sizeDraft()" (input)="sizeDraft.set(sizeName.value); draftChange.emit()" />
            <button type="button" [disabled]="busy" (click)="addOption.emit({kind:'SIZE', name:sizeName.value, colorHex:null})">Salvar tamanho</button>
          }
        }
        @if (sizeInvalid) { <p class="field-error" role="alert">Informe um tamanho válido.</p> }
      </fieldset>
    }
    @if (error) { <p class="field-error" role="alert">{{ error }}</p> }
  `,
  styles: [`
    :host { display:block; min-width:0; }
    fieldset { border:1px solid #dfe7df; margin:16px 0; padding:14px; min-width:0; }
    legend { font-weight:650; font-size:.8rem; }
    .choices { display:flex; flex-wrap:wrap; gap:7px; margin:8px 0; }
    .choice { font:inherit; font-size:.8rem; background:white; border:1px solid #dfe7df; padding:7px 10px; }
    .selected { border-color:#294b40; background:#edf3eb; }
    .color-choices { flex-wrap:nowrap; overflow-x:auto; padding:8px 2px 12px; scrollbar-color:#a8bbaa #edf3eb; }
    .color-choice { flex:0 0 auto; display:flex; align-items:center; gap:8px; white-space:nowrap; border-radius:24px; padding:5px 12px 5px 5px; }
    .swatch { display:inline-block; width:30px; height:30px; flex:none; border:1px solid #aeb8ac; border-radius:50%; }
    label { display:block; font-size:.8rem; margin-top:10px; }
    input { font:inherit; padding:8px; max-width:100%; }
    input[type=color] { padding:2px; width:48px; height:35px; }
  `],
})
export class VariantOptionsComponent {
  @Input() hasColor = false;
  @Input() hasSize = false;
  @Input() numeric = false;
  @Input() color = '';
  @Input() size = '';
  @Input() colorInvalid = false;
  @Input() sizeInvalid = false;
  @Input() busy = false;
  @Input() error = '';
  @Input() options: VariantOption[] = [];
  @Output() colorChange = new EventEmitter<string>();
  @Output() sizeChange = new EventEmitter<string>();
  @Output() addOption = new EventEmitter<{ kind: 'COLOR' | 'SIZE'; name: string; colorHex: string | null }>();
  @Output() draftChange = new EventEmitter<void>();
  protected readonly colorDraft = signal('');
  protected readonly hexDraft = signal('#557799');
  protected readonly sizeDraft = signal('');
  protected readonly addingColor = signal(false);
  protected readonly addingSize = signal(false);
  private readonly defaultColors = [
    ['Preto', '#1d1d1d'], ['Branco', '#ffffff'], ['Cinza', '#8c8c8c'], ['Cinza claro', '#c5c5c5'],
    ['Azul', '#2563a8'], ['Azul claro', '#8ac5e8'], ['Azul escuro', '#1d345d'],
    ['Vermelho', '#c52f36'], ['Vermelho claro', '#ef8b8b'], ['Vermelho escuro', '#842d34'],
    ['Verde', '#389054'], ['Verde claro', '#a8d7a2'], ['Verde escuro', '#20593d'],
    ['Amarelo', '#efcd48'], ['Laranja', '#e88d3c'], ['Rosa', '#e69db7'], ['Roxo', '#7957a2'],
    ['Marrom', '#79533b'], ['Bege', '#e4d3b3'],
  ].map(([name, colorHex]) => ({ name, colorHex }));
  protected get colors() {
    const defaults = new Set(this.defaultColors.map((option) => option.name.toLowerCase()));
    return [...this.defaultColors, ...this.options.filter((option) => option.kind === 'COLOR' && !defaults.has(option.name.toLowerCase()))];
  }
  protected get sizes() {
    const defaults = ['PP', 'P', 'M', 'G', 'GG'];
    return [...defaults.map((name) => ({ name })), ...this.options.filter((option) => option.kind === 'SIZE' && !defaults.includes(option.name.toUpperCase()))];
  }
}
