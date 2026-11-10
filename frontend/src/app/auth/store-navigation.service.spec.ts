import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { StoreNavigation } from './store-navigation.service';

describe('store navigation', () => {
  const assign = vi.fn();
  const location = { hostname: 'localhost', protocol: 'http:', port: '4200', assign };
  beforeEach(() => {
    assign.mockReset(); location.hostname = 'localhost';
    TestBed.configureTestingModule({ providers: [{ provide: DOCUMENT, useValue: { location } }] });
  });
  it('builds the store origin without forwarding credentials', () => {
    const navigation = TestBed.inject(StoreNavigation);
    expect(navigation.address('minha-loja')).toBe('http://minha-loja.localhost:4200');
    navigation.open(`${navigation.address('minha-loja')}/entrar`);
    expect(assign).toHaveBeenCalledWith('http://minha-loja.localhost:4200/entrar');
  });
  it('rejects invalid subdomains and does not invent a production domain', () => {
    const navigation = TestBed.inject(StoreNavigation);
    for (const invalid of ['', '-loja', 'loja-', 'loja/entrar', 'a'.repeat(64), 'evil.example']) {
      expect(navigation.address(invalid)).toBeNull();
    }
    location.hostname = 'platform.example';
    expect(navigation.address('loja')).toBeNull();
    expect(assign).not.toHaveBeenCalled();
  });
});
