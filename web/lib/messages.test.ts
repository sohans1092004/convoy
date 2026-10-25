import { describe, expect, it } from 'vitest';
import en from '../messages/en.json';
import hi from '../messages/hi.json';

type Tree = { [k: string]: string | Tree };
const flat = (o: Tree, p = ''): Record<string, string> =>
  Object.entries(o).reduce((acc, [k, v]) => ({ ...acc, ...(typeof v === 'string' ? { [p + k]: v } : flat(v, `${p}${k}.`)) }), {});
// Top-level ICU arguments ({name} or {name, plural, ...}). Plural branches ("one {buyer}") are text, not arguments.
const PLURAL_BRANCH = /\b(?:=\d+|zero|one|two|few|many|other)\s*\{[^{}]*\}/g;
const args = (s: string) => [...s.replace(PLURAL_BRANCH, '').matchAll(/\{(\w+)(?=[,}])/g)]
  .map((m) => m[1]).filter((a, i, all) => all.indexOf(a) === i).sort();

describe('messages', () => {
  const E = flat(en as Tree), H = flat(hi as Tree);
  it('hi has exactly the en keys (missing keys would fall back to English silently)', () => {
    expect(Object.keys(H).sort()).toEqual(Object.keys(E).sort());
  });
  it('every hi string uses the same placeholders as its en source', () => {
    const wrong = Object.keys(E).filter((k) => JSON.stringify(args(E[k])) !== JSON.stringify(args(H[k] ?? '')));
    expect(wrong).toEqual([]);
  });
});
