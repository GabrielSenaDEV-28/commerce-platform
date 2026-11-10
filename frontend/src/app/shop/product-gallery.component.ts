import { Component, ElementRef, Input, OnChanges, ViewChild, signal } from '@angular/core';
import { UI } from '../ui/ui';

@Component({
  selector: 'app-product-gallery',
  imports: [...UI],
  template: `
    <section aria-label="Fotos do produto">
      <div class="main-photo">
        @if (photos.length && !failed()[photo()]) {
          <button type="button" class="expand" aria-label="Ampliar imagem do produto" (click)="open()">
            <img [src]="photos[photo()]" [alt]="name + ', imagem ' + (photo() + 1)" (error)="imageFailed(photo())" />
            <span>Ampliar imagem</span>
          </button>
        } @else {
          <div class="no-photo" role="status"><span aria-hidden="true">c.</span><p>{{ photos.length ? 'Não foi possível carregar esta imagem.' : 'Este produto ainda não tem fotos' }}</p>
            @if (photos.length) { <button type="button" (click)="retry()">Tentar carregar novamente</button> }
          </div>
        }
        @if (photos.length > 1) {
          <div class="gallery-controls"><button type="button" aria-label="Imagem anterior" (click)="next(-1)">←</button><span aria-live="polite">{{ photo() + 1 }} / {{ photos.length }}</span><button type="button" aria-label="Próxima imagem" (click)="next(1)">→</button></div>
        }
      </div>
      @if (photos.length > 1) {
        <div class="thumbnails" aria-label="Escolher imagem">@for (url of photos; track $index; let index = $index) {
          <button type="button" [class.selected]="photo() === index" [attr.aria-pressed]="photo() === index" [attr.aria-label]="'Ver imagem ' + (index + 1)" (click)="photo.set(index)">
            @if (!failed()[index]) { <img [src]="url" alt="" loading="lazy" (error)="imageFailed(index)" /> } @else { <span>Foto {{ index + 1 }} indisponível</span> }
          </button>
        }</div>
      }
    </section>
    <dialog #viewer class="viewer" aria-labelledby="gallery-title" aria-describedby="gallery-help" (keydown)="onKey($event)">
      <div class="viewer-heading"><h2 id="gallery-title">{{ name }}</h2><button autofocus type="button" aria-label="Fechar imagem ampliada" (click)="close()">Fechar ✕</button></div>
      <p id="gallery-help">Use as setas para trocar a imagem e Escape para fechar.</p>
      @if (photos.length && !failed()[photo()]) { <img class="enlarged" [src]="photos[photo()]" [alt]="name + ', imagem ' + (photo() + 1)" (error)="imageFailed(photo())" /> }
      @else { <p role="status">Não foi possível carregar esta imagem.</p><button type="button" (click)="retry()">Tentar carregar novamente</button> }
      <div class="viewer-controls"><button type="button" [disabled]="photos.length < 2" aria-label="Imagem ampliada anterior" (click)="next(-1)">← Anterior</button><span aria-live="polite">{{ photo() + 1 }} / {{ photos.length }}</span><button type="button" [disabled]="photos.length < 2" aria-label="Próxima imagem ampliada" (click)="next(1)">Próxima →</button></div>
    </dialog>`,
  styles: [`
    :host { display:block; min-width:0; }
    .main-photo { position:relative; aspect-ratio:4/5; background:#edf1e9; border-radius:12px; overflow:hidden; }
    .expand { display:block; width:100%; height:100%; padding:0; border:0; position:relative; cursor:zoom-in; background:transparent; }
    .expand img { width:100%; height:100%; object-fit:contain; } .expand > span { position:absolute; top:16px; right:16px; padding:8px 12px; background:white; border-radius:20px; font-size:.75rem; }
    .no-photo { height:100%; display:flex; flex-direction:column; justify-content:center; align-items:center; padding:24px; text-align:center; color:#526b56; }.no-photo > span { font:4rem Georgia,serif; }
    .gallery-controls, .viewer-controls { display:flex; align-items:center; justify-content:space-between; gap:10px; font-size:.8rem; }
    .gallery-controls { position:absolute; bottom:16px; left:16px; right:16px; }.gallery-controls > span { background:white; padding:8px 14px; border-radius:20px; }
    .thumbnails { display:flex; gap:10px; overflow-x:auto; padding:14px 3px; }.thumbnails button { flex:0 0 72px; height:88px; padding:3px; background:#edf1e9; }
    .thumbnails .selected { border-color:#294b40; outline:1px solid #294b40; }.thumbnails img { width:100%; height:100%; object-fit:cover; }.thumbnails span { font-size:.65rem; }
    dialog.viewer { width:min(1100px,calc(100% - 24px)); }.viewer-heading { display:flex; justify-content:space-between; gap:16px; align-items:start; } h2 { font:400 1.5rem Georgia,serif; margin:8px 0; overflow-wrap:anywhere; }
    .enlarged { display:block; width:100%; height:60vh; object-fit:contain; background:#f3f5ef; margin:16px 0; } p { font-size:.82rem; line-height:1.6; }.viewer-controls { margin-top:16px; flex-wrap:wrap; }
  `],
})
export class ProductGalleryComponent implements OnChanges {
  @Input() photos: string[] = [];
  @Input() name = '';
  @ViewChild('viewer', { read: ElementRef }) private viewer?: ElementRef<HTMLDialogElement>;
  protected readonly photo = signal(0);
  protected readonly failed = signal<Record<number, boolean>>({});
  ngOnChanges(): void { this.photo.set(0); this.failed.set({}); this.close(); }
  protected imageFailed(index: number): void { this.failed.update((failed) => ({ ...failed, [index]: true })); }
  protected retry(): void { this.failed.update((failed) => ({ ...failed, [this.photo()]: false })); }
  protected next(direction: number): void {
    if (this.photos.length) this.photo.set((this.photo() + direction + this.photos.length) % this.photos.length);
  }
  protected open(): void { this.viewer?.nativeElement.showModal(); }
  protected close(): void { if (this.viewer?.nativeElement.open) this.viewer.nativeElement.close(); }
  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault(); this.next(event.key === 'ArrowRight' ? 1 : -1);
    }
  }
}
