import { TestBed } from '@angular/core/testing';
import { FavoritesService } from './favorites.service';
import { vi } from 'vitest';

describe('local favorites', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => { localStorage.clear(); vi.restoreAllMocks(); });
  it('toggles products independently and shares state during navigation', () => {
    const favorites = TestBed.inject(FavoritesService);
    favorites.toggle('p1'); favorites.toggle('p2');
    expect(TestBed.inject(FavoritesService).has('p1')).toBe(true);
    favorites.toggle('p1');
    expect(favorites.has('p1')).toBe(false); expect(favorites.has('p2')).toBe(true);
    expect(TestBed.runInInjectionContext(() => new FavoritesService()).has('p2')).toBe(true);
  });
  it('stores only product IDs in origin-scoped storage and accepts cross-tab updates', () => {
    const favorites = TestBed.inject(FavoritesService);
    favorites.toggle('p1');
    expect(JSON.parse(localStorage.getItem('commerce.favorites')!)).toEqual(['p1']);
    localStorage.setItem('commerce.favorites', '["p2"]');
    window.dispatchEvent(new StorageEvent('storage', { key: 'commerce.favorites' }));
    expect(favorites.ids()).toEqual(['p2']); expect(favorites.has('p1')).toBe(false);
  });
  it('reports corrupt data rather than silently pretending there are no favorites', () => {
    localStorage.setItem('commerce.favorites', '{"invalid":true}');
    const favorites = TestBed.inject(FavoritesService);
    expect(favorites.error()).toContain('Não foi possível recuperar');
    expect(localStorage.getItem('commerce.favorites')).toBe('{"invalid":true}');
    favorites.clear();
    expect(favorites.error()).toBe(''); expect(favorites.ids()).toEqual([]);
  });
  it('keeps in-memory functionality and reports when persistence is blocked', () => {
    const favorites = TestBed.inject(FavoritesService);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Blocked'); });
    favorites.toggle('p1');
    expect(favorites.has('p1')).toBe(true);
    expect(favorites.error()).toContain('apenas nesta aba');
  });
});
