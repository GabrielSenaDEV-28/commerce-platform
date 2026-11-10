import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UI } from '../ui/ui';

interface Category { id: string; name: string; active: boolean; hasColor: boolean; hasSize: boolean; sizeFormat: string | null }
@Component({
  selector: 'app-category-details',
  imports: [RouterLink, ...UI],
  template: `<div class="admin"><header><a routerLink="/" class="brand">c. <span>commerce</span></a><span>CATEGORIA</span><a routerLink="/painel/categorias">Voltar às categorias</a></header>
    <main><nav class="breadcrumbs" aria-label="Navegação estrutural"><a routerLink="/painel/categorias">Categorias</a><span>/</span><span aria-current="page">{{ category()?.name ?? 'Categoria' }}</span></nav>
    @if (loading()) { <p role="status">Carregando categoria...</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button type="button" [disabled]="loading()" (click)="load()">Tentar novamente</button> }
    @if (category(); as item) { <section class="panel"><h1>{{ item.name }}</h1><p>{{ item.active ? 'Ativa' : 'Inativa' }}</p>
      <p>Cor: {{ item.hasColor ? 'Sim' : 'Não' }}</p><p>Tamanho: {{ item.hasSize ? (item.sizeFormat === 'NUMERIC' ? 'Numérico' : 'Letras') : 'Não' }}</p>
      @if (item.active) { <a routerLink="/painel/categorias" [queryParams]="{editar:item.id}">Editar categoria</a> }
    </section> }</main></div>`,
  styleUrl: './merchant-admin.component.css',
})
export class CategoryDetailsComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly destroy = inject(DestroyRef);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  protected readonly category = signal<Category | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  constructor() { this.load(); }
  protected load(): void {
    this.loading.set(true); this.error.set('');
    this.http.get<Category>(`/api/item-types/${encodeURIComponent(this.id)}`,
      { headers: new HttpHeaders({ Authorization: `Bearer ${this.auth.merchantAccessToken()}` }) }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (category) => { this.category.set(category); this.loading.set(false); },
      error: () => { this.loading.set(false); this.error.set('Categoria não encontrada ou indisponível.'); },
    });
  }
}
