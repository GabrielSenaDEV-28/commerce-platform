import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { CartService } from '../shop/cart.service';
import { MobileMenuComponent } from './mobile-menu.component';
import { UI } from './ui';

@Component({
  selector: 'app-shop-header',
  imports: [...UI, RouterLink, RouterLinkActive, MobileMenuComponent],
  host: { '[class.scrolled]': 'scrolled()', '(window:scroll)': 'updateScroll()' },
  template: `
    <header class="header-inner">
      <app-mobile-menu label="Explorar loja">
        <a routerLink="/">Início</a>
        <a routerLink="/" fragment="colecao">Explorar produtos</a>
        <span>Categorias · Em breve</span>
      </app-mobile-menu>
      <a class="wordmark" routerLink="/" aria-label="Commerce, início da loja">
        <span class="mark" aria-hidden="true">c.</span>
        <span>commerce<small>SUA LOJA ONLINE</small></span>
      </a>
      <nav class="browse" aria-label="Navegação da loja">
        <a routerLink="/" fragment="colecao">Explorar produtos</a>
        <span class="coming-soon">Categorias <small>Em breve</small></span>
      </nav>
      <nav class="header-actions" aria-label="Acessos rápidos">
        <a class="icon desktop-account" [routerLink]="accountPath()" [class.active]="accountActive()"
          [attr.aria-current]="routePath() === accountPath() ? 'page' : null" [attr.aria-label]="accountLabel()" [attr.title]="accountLabel()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle class="fillable" cx="12" cy="8" r="3.3"/><path class="fillable" d="M5 20c0-3.5 2.8-5.8 7-5.8s7 2.3 7 5.8Z"/></svg>
        </a>
        <app-mobile-menu #account [label]="accountLabel()" [active]="accountActive()"
          side="right" [profile]="true" [busy]="signingOut() || cart.busy()">
          @switch (auth.accountKind()) {
            @case ('customer') {
              <a routerLink="/conta/perfil">Dados pessoais</a>
              <a routerLink="/conta/enderecos">Endereços</a>
              <a routerLink="/conta/pedidos">Meus pedidos</a>
              <a routerLink="/conta/pagamentos">Meios de pagamento</a>
              <a routerLink="/conta/seguranca">Segurança e acesso</a>
            }
            @case ('merchant') {
              <a routerLink="/painel/perfil">Perfil e loja</a>
              <a routerLink="/painel/entrega">Entrega e retirada</a>
              <a routerLink="/painel/categorias">Categorias</a>
              <a routerLink="/painel/produtos">Produtos</a>
              <a routerLink="/painel/pedidos">Pedidos</a>
              <a routerLink="/painel/promocoes">Promoções</a>
              <a routerLink="/painel/vendas">Painel de vendas</a>
              <a routerLink="/painel/funcionarios">Funcionários</a>
              <a routerLink="/painel/personalizacao">Personalização</a>
            }
            @default {
              <a routerLink="/entrar">Entrar na minha conta</a>
              <a routerLink="/cadastro">Criar conta</a>
            }
          }
          @if (auth.accountKind()) {
            <button type="button" class="signout" [disabled]="signingOut() || cart.busy()" (click)="logout(account)">{{ signingOut() ? 'Saindo...' : 'Sair da conta' }}</button>
          }
        </app-mobile-menu>
        <a class="icon" routerLink="/favoritos" routerLinkActive="active" ariaCurrentWhenActive="page" aria-label="Favoritos" title="Favoritos">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path class="fillable" d="M20.8 8.6c0 4.1-8.8 10-8.8 10s-8.8-5.9-8.8-10a4.5 4.5 0 0 1 8.8-1.3 4.5 4.5 0 0 1 8.8 1.3Z"/></svg>
        </a>
        <a class="icon" routerLink="/carrinho" routerLinkActive="active" ariaCurrentWhenActive="page" [attr.aria-label]="'Sacola com ' + cart.count() + ' itens'" title="Sacola">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path class="fillable" d="M5 8h14l1 12H4L5 8Z"/><path d="M9 8V6a3 3 0 0 1 6 0v2"/></svg>
          @if (cart.count()) { <span class="badge" aria-hidden="true">{{ cart.count() }}</span> }
        </a>
      </nav>
    </header>
  `,
  styles: [`
    :host { position:sticky; top:0; z-index:50; display:block; background:#f9f8f3; color:#294b40;
      box-shadow:inset 0 -1px #dce5da, 0 2px 12px #294b4014; transition:background-color .2s, box-shadow .2s; }
    :host(.scrolled) { background:#e8efe7; box-shadow:0 5px 16px #294b4026; }
    .header-inner { height:var(--shop-header-height); max-width:76rem; margin:auto; padding:0 clamp(.5rem, 3vw, 1.5rem);
      display:grid; grid-template-columns:1fr auto 1fr; align-items:center; column-gap:clamp(.15rem, 2vw, 1.5rem); }
    .wordmark { grid-column:1; justify-self:start; display:flex; align-items:center; gap:10px; text-decoration:none; color:#263d36;
      font:bold 1.65rem/.8 Georgia, serif; letter-spacing:-.05em; white-space:nowrap; }
    .wordmark small { display:block; margin-top:7px; font:600 .49rem system-ui, sans-serif; letter-spacing:.15em; color:#576b60; }
    .mark { background:#294b40; color:white; width:39px; height:39px; display:grid; place-items:center;
      border-radius:50%; font-size:1.4rem; padding-bottom:5px; }
    .browse { grid-column:2; display:flex; align-items:center; gap:clamp(1rem, 3vw, 2rem); font-size:.875rem; font-weight:600; }
    .browse > * { display:inline-flex; align-items:center; gap:.4rem; min-height:44px; white-space:nowrap; transition:transform .18s ease; }
    .browse a { color:inherit; }
    .browse a:is(:hover, :focus-visible) { transform:translateY(-3px); text-decoration:underline; text-underline-offset:.35em; }
    .coming-soon { color:#576b60; }
    @media(hover:hover) { .coming-soon:hover { transform:translateY(-3px); } }
    .coming-soon small { font-size:.625rem; letter-spacing:.07em; text-transform:uppercase; }
    .header-actions { grid-column:3; justify-self:end; display:flex; align-items:center; gap:.125rem; }
    .icon { display:grid; place-items:center; position:relative; width:44px; height:44px; border-radius:.5rem; color:var(--color-brand); }
    svg { width:22px; height:22px; fill:none; stroke:currentColor; stroke-width:1.6; stroke-linecap:round; stroke-linejoin:round; }
    .fillable { transition:fill .16s ease; }
    .icon:is(:focus-visible, .active) .fillable { fill:currentColor; }
    @media(hover:hover) { .icon:hover .fillable { fill:currentColor; } }
    .badge { position:absolute; inset:.1rem .05rem auto auto; min-width:1rem; padding:.1rem .2rem; background:var(--color-brand); color:white; border-radius:1rem; font-size:.65rem; text-align:center; }
    @media(max-width:720px) {
      .header-inner { display:flex; }
      .browse, .desktop-account, .wordmark small { display:none; }
      .header-actions { margin-inline-start:auto; }
      .wordmark { font-size:1.35rem; gap:.35rem; }
      .mark { width:30px; height:30px; font-size:1.1rem; padding-bottom:3px; }
    }
    @media(max-width:360px) {
      .wordmark { font-size:1.15rem; }
      .mark { display:none; }
    }
    @media(prefers-reduced-motion:reduce) {
      :host, .browse > *, .fillable { transition:none; }
      .browse a:is(:hover, :focus-visible), .coming-soon:hover { transform:none; }
    }
  `],
})
export class ShopHeaderComponent {
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  private readonly router = inject(Router);
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly destroy = inject(DestroyRef);
  protected readonly signingOut = signal(false);
  protected readonly scrolled = signal(false);
  private readonly url = toSignal(this.router.events.pipe(
    filter((event): event is NavigationEnd => event instanceof NavigationEnd),
    map((event) => event.urlAfterRedirects),
  ), { initialValue: this.router.url });
  protected readonly routePath = computed(() => this.url().split(/[?#]/)[0]);
  protected readonly accountPath = computed(() => this.auth.accountKind() === 'merchant' ? '/painel/perfil' :
    this.auth.accountKind() === 'customer' ? '/conta/perfil' : '/entrar');
  protected readonly accountLabel = computed(() => this.auth.accountKind() === 'merchant' ? 'Administração' :
    this.auth.accountKind() === 'customer' ? 'Minha conta' : 'Entrar na minha conta');
  protected readonly accountActive = computed(() => {
    const path = this.routePath();
    const section = this.accountPath().split('/')[1];
    return path === `/${section}` || path.startsWith(`/${section}/`) || (section === 'entrar' && path === '/cadastro');
  });

  constructor() {
    afterNextRender(() => this.updateScroll());
  }

  protected updateScroll(): void {
    this.scrolled.set((this.window?.scrollY ?? 0) > 8);
  }

  protected logout(menu: MobileMenuComponent): void {
    if (this.signingOut() || this.cart.busy() || !this.auth.accountKind()) return;
    this.signingOut.set(true);
    this.auth.logout().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => { this.signingOut.set(false); menu.close(); void this.router.navigateByUrl('/entrar'); },
      error: () => { this.signingOut.set(false); menu.close(); },
    });
  }
}
