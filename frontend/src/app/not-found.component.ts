import { UI } from './ui/ui';
import { Component, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [...UI, RouterLink],
  template: `<main class="not-found"><span>404 · PÁGINA NÃO ENCONTRADA</span><h1>Este caminho não existe.</h1>
    <p>Confira o endereço ou volte para {{ store ? 'a loja' : 'o acesso do lojista' }}.</p>
    <a class="button" [routerLink]="store ? '/' : '/entrar'">{{ store ? 'Voltar à loja' : 'Localizar minha loja' }}</a></main>`,
  styles: [`.not-found { max-width:680px; margin:15vh auto; padding:24px; color:#294b40; }
    span { font-size:.75rem; letter-spacing:.12em; } h1 { font:400 2.8rem Georgia,serif; }
    p { color:#627568; }`],
})
export class NotFoundComponent {
  protected readonly store = !['localhost', '127.0.0.1'].includes(inject(DOCUMENT).location.hostname);
}
