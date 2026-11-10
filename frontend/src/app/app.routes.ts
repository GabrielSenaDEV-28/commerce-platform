import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { RegisterComponent } from './register/register.component';
import { StorefrontComponent } from './storefront/storefront.component';
import { DOCUMENT } from '@angular/common';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { CustomerAccountComponent } from './customer-account/customer-account.component';
import { MerchantAdminComponent } from './merchant-admin/merchant-admin.component';
import { requireAccount } from './auth/session.guard';
import { leaveEditablePage } from './unsaved-changes';
import { NotFoundComponent } from './not-found.component';

export const routes: Routes = [
  { path: 'produtos/:id', loadComponent: () => import('./shop/product-page.component').then((m) => m.ProductPageComponent), data: { reloadOnParamChange: true }, canDeactivate: [leaveEditablePage] },
  { path: 'carrinho', loadComponent: () => import('./shop/cart-page.component').then((m) => m.CartPageComponent), canDeactivate: [leaveEditablePage] },
  { path: 'painel/login', redirectTo: 'entrar', pathMatch: 'full' },
  { path: 'painel/cadastro', component: RegisterComponent, data: { kind: 'merchant' }, canDeactivate: [leaveEditablePage] },
  { path: 'painel', pathMatch: 'full', redirectTo: 'painel/categorias' },
  { path: 'painel/produtos/:id/variantes/:variantId', loadComponent: () => import('./merchant-admin/product-details.component').then((m) => m.ProductDetailsComponent), data: { reloadOnParamChange: true },
    canActivate: [requireAccount('merchant')], canDeactivate: [leaveEditablePage] },
  { path: 'painel/produtos/:id', loadComponent: () => import('./merchant-admin/product-details.component').then((m) => m.ProductDetailsComponent), data: { reloadOnParamChange: true },
    canActivate: [requireAccount('merchant')], canDeactivate: [leaveEditablePage] },
  { path: 'painel/categorias/:id', loadComponent: () => import('./merchant-admin/category-details.component').then((m) => m.CategoryDetailsComponent), data: { reloadOnParamChange: true }, canActivate: [requireAccount('merchant')] },
  { path: 'painel/:section', component: MerchantAdminComponent,
    canMatch: [(_route, segments) => ['perfil', 'categorias', 'produtos', 'pedidos', 'promocoes', 'vendas', 'funcionarios', 'personalizacao'].includes(segments[1]?.path)],
    canActivate: [requireAccount('merchant')], canDeactivate: [leaveEditablePage] },
  { path: 'entrar', component: LoginComponent,
    canActivate: [() => !inject(AuthService).accountKind() || inject(Router).parseUrl(inject(AuthService).accountKind() === 'merchant' ? '/painel/perfil' : '/conta/perfil')] },
  { path: 'cadastro', component: RegisterComponent, data: { kind: 'customer' }, canDeactivate: [leaveEditablePage] },
  { path: 'conta', pathMatch: 'full', redirectTo: 'conta/perfil' },
  { path: 'conta/:section', component: CustomerAccountComponent,
    canMatch: [(_route, segments) => ['perfil', 'enderecos', 'pedidos', 'pagamentos', 'seguranca'].includes(segments[1]?.path)],
    canActivate: [requireAccount('customer')], canDeactivate: [leaveEditablePage] },
  { path: '', pathMatch: 'full', component: StorefrontComponent, canDeactivate: [leaveEditablePage],
    canMatch: [() => !['localhost', '127.0.0.1'].includes(inject(DOCUMENT).location.hostname)] },
  { path: '', pathMatch: 'full', redirectTo: 'painel/cadastro' },
  { path: '**', component: NotFoundComponent },
];
