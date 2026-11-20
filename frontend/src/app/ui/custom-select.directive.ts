import { AfterViewInit, Directive, DoCheck, ElementRef, OnDestroy, Renderer2, afterEveryRender, inject } from '@angular/core';

let nextSelectId = 0;

@Directive({ selector: 'select' })
export class CustomSelectDirective implements AfterViewInit, DoCheck, OnDestroy {
  private readonly select = inject<ElementRef<HTMLSelectElement>>(ElementRef).nativeElement;
  private readonly document = this.select.ownerDocument;
  private readonly renderer = inject(Renderer2);
  private readonly removers: (() => void)[] = [];
  private button?: HTMLButtonElement;
  private list?: HTMLDivElement;
  private options: HTMLElement[] = [];
  private signature = '';
  private active = -1;
  private open = false;
  private search = '';
  private searchedAt = 0;
  private readonly id = `ui-select-${++nextSelectId}`;

  constructor() { afterEveryRender(() => this.sync()); }

  ngAfterViewInit(): void {
    const select = this.select;
    const button = this.document.createElement('button');
    const list = this.document.createElement('div');
    this.button = button; this.list = list;
    button.type = 'button'; button.className = 'ui-select-trigger';
    button.setAttribute('role', 'combobox');
    button.setAttribute('aria-haspopup', 'listbox');
    button.setAttribute('aria-controls', this.id);
    button.setAttribute('aria-expanded', 'false');
    list.id = this.id; list.className = 'ui-select-list'; list.hidden = true;
    list.setAttribute('role', 'listbox');
    select.classList.add('ui-select-native');
    select.insertAdjacentElement('afterend', button);
    this.document.body.append(list);
    this.removers.push(
      this.renderer.listen(button, 'click', () => this.open ? this.close() : this.show()),
      this.renderer.listen(button, 'keydown', (event: KeyboardEvent) => this.keydown(event)),
      this.renderer.listen(button, 'blur', () => {
        if (!this.open) select.dispatchEvent(new Event('blur'));
      }),
      this.renderer.listen(select, 'change', () => this.sync()),
      this.renderer.listen(select, 'invalid', (event: Event) => { event.preventDefault(); button.focus(); }),
      this.renderer.listen(this.document, 'pointerdown', (event: PointerEvent) => {
        if (event.target instanceof Node && !button.contains(event.target) && !list.contains(event.target)) this.close();
      }),
      this.renderer.listen(this.document, 'scroll', () => this.position(), { capture: true }),
      this.renderer.listen('window', 'resize', () => this.position()),
      this.renderer.listen(list, 'pointerdown', (event: PointerEvent) => event.preventDefault()),
      this.renderer.listen(list, 'click', (event: MouseEvent) => {
        const target = event.target instanceof Element ? event.target.closest<HTMLElement>('[role="option"]') : null;
        if (target && target.getAttribute('aria-disabled') !== 'true') this.choose(Number(target.dataset['index']));
      }),
    );
    for (const label of Array.from(select.labels ?? [])) {
      if (!label.id) label.id = `${this.id}-label-${this.removers.length}`;
      this.removers.push(this.renderer.listen(label, 'click', (event: MouseEvent) => {
        if (event.target === label) { event.preventDefault(); button.focus(); }
      }));
    }
    this.sync();
  }

  ngDoCheck(): void { this.sync(); }

  private sync(): void {
    if (!this.button || !this.list) return;
    const select = this.select;
    this.button.disabled = select.matches(':disabled');
    this.button.textContent = select.selectedOptions[0]?.textContent ?? 'Selecione';
    this.button.setAttribute('aria-required', String(select.required));
    this.button.setAttribute('aria-invalid', select.getAttribute('aria-invalid') ?? 'false');
    const labelledBy = select.getAttribute('aria-labelledby') ?? Array.from(select.labels ?? []).map(label => label.id).join(' ');
    if (labelledBy) this.button.setAttribute('aria-labelledby', labelledBy);
    if (select.hasAttribute('aria-label')) this.button.setAttribute('aria-label', select.getAttribute('aria-label')!);
    const description = select.getAttribute('aria-describedby');
    if (description) this.button.setAttribute('aria-describedby', description);
    else this.button.removeAttribute('aria-describedby');
    const signature = JSON.stringify(Array.from(select.options).map(option => [option.value, option.text, option.disabled]));
    if (signature !== this.signature) {
      this.signature = signature;
      this.list.replaceChildren();
      this.options = Array.from(select.options).map((option, index) => {
        const item = this.document.createElement('div');
        item.id = `${this.id}-${index}`; item.dataset['index'] = String(index);
        item.textContent = option.text; item.setAttribute('role', 'option');
        item.setAttribute('aria-disabled', String(option.disabled));
        this.list!.append(item);
        return item;
      });
    }
    this.options.forEach((option, index) => option.setAttribute('aria-selected', String(index === select.selectedIndex)));
    if (this.button.disabled) this.close();
    if (this.open) this.position();
  }

  private show(): void {
    if (!this.button || !this.list || this.button.disabled) return;
    this.sync();
    this.open = true; this.list.hidden = false;
    this.button.setAttribute('aria-expanded', 'true');
    this.activate(this.select.selectedIndex);
    this.position();
  }

  private close(): void {
    if (!this.open || !this.button || !this.list) return;
    this.open = false; this.list.hidden = true;
    this.button.setAttribute('aria-expanded', 'false');
    this.button.removeAttribute('aria-activedescendant');
    this.select.dispatchEvent(new Event('blur'));
  }

  private choose(index: number): void {
    if (!this.select.options[index] || this.select.options[index].disabled) return;
    this.select.selectedIndex = index;
    this.select.dispatchEvent(new Event('change', { bubbles: true }));
    this.sync(); this.close(); this.button?.focus();
  }

  private activate(index: number): void {
    this.active = index;
    this.options.forEach((option, i) => option.classList.toggle('active', i === index));
    if (this.options[index]) {
      this.button?.setAttribute('aria-activedescendant', this.options[index].id);
      this.options[index].scrollIntoView?.({ block: 'nearest' });
    }
  }

  private keydown(event: KeyboardEvent): void {
    if (event.key === 'Tab') { this.close(); return; }
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); this.close(); return; }
    if (['Enter', ' '].includes(event.key)) {
      event.preventDefault();
      if (this.open) this.choose(this.active); else this.show();
      return;
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const wasOpen = this.open;
      if (!wasOpen) this.show();
      const enabled = Array.from(this.select.options).map((option, index) => option.disabled ? -1 : index).filter(index => index >= 0);
      const current = enabled.indexOf(this.active);
      const index = event.key === 'Home' ? enabled[0] : event.key === 'End' ? enabled.at(-1)
        : enabled[Math.max(0, Math.min(enabled.length - 1, current + (wasOpen ? event.key === 'ArrowDown' ? 1 : -1 : 0)))];
      if (index !== undefined) this.activate(index);
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      if (!this.open) this.show();
      this.search = Date.now() - this.searchedAt > 700 ? event.key : this.search + event.key;
      this.searchedAt = Date.now();
      const index = Array.from(this.select.options).findIndex(option => !option.disabled && option.text.toLocaleLowerCase().startsWith(this.search.toLocaleLowerCase()));
      if (index >= 0) this.activate(index);
    }
  }

  private position(): void {
    if (!this.open || !this.button || !this.list) return;
    const rect = this.button.getBoundingClientRect();
    const height = this.document.documentElement.clientHeight || this.document.defaultView!.innerHeight;
    const below = height - rect.bottom - 12;
    const above = rect.top - 12;
    const up = below < 180 && above > below;
    Object.assign(this.list.style, {
      left: `${rect.left}px`, width: `${rect.width}px`, maxHeight: `${Math.max(60, Math.min(260, up ? above : below))}px`,
      top: up ? 'auto' : `${rect.bottom + 6}px`, bottom: up ? `${height - rect.top + 6}px` : 'auto',
    });
  }

  ngOnDestroy(): void {
    this.removers.forEach(remove => remove());
    this.button?.remove(); this.list?.remove();
  }
}
