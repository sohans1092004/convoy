// ADR-011: the TypeScript engine reproduces every pricing and tier vector byte for byte (canonical JSON).
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { canonical, compute, countConfirmed, resolveTiers, type Rules } from '../src';

const dir = join(__dirname, '..', '..', '..', 'spec-tools', 'vectors');
const read = (n: string) => JSON.parse(readFileSync(join(dir, n), 'utf8'));
const rules: Rules = read('rules.json');
const sha256 = (v: unknown) => createHash('sha256').update(canonical(v), 'utf8').digest('hex');
// Extra T23 fields describe downstream order behaviour, not engine output (gen_vectors.py).
const T23_EXTRAS = ['post_deadline_cancellations', 'price_after_cancellations_paise', 'per_booking_refund_reason'];

describe('pricing.json', () => {
  const cases: any[] = read('pricing.json').cases;
  it('has 54 cases', () => expect(cases).toHaveLength(54));
  it.each(cases.map((c: any) => [c.id, c] as [string, any]))('%s', (_id, c: any) => {
    const output = compute(c.input, rules);
    expect(canonical(output)).toBe(canonical(c.expected));
    expect(sha256({ inputs: c.input, ruleset_version: c.input.ruleset_version, output })).toBe(c.snapshot_sha256);
  });
});

describe('tiers.json', () => {
  const cases: any[] = read('tiers.json').cases;
  it('has 30 cases', () => expect(cases).toHaveLength(30));
  it.each(cases.map((c: any) => [c.id, c] as [string, any]))('%s', (id, c: any) => {
    const i = c.input;
    const n = i.confirmations ? countConfirmed(i.confirmations, i.confirmation_deadline) : i.confirmed_count;
    const expected = { ...c.expected };
    if (id === 'T23') {
      expect(expected.price_after_cancellations_paise).toBe(expected.resolved_price_paise);
      T23_EXTRAS.forEach((k) => delete expected[k]);
    }
    expect(canonical(resolveTiers(i.tiers, i.min_fulfilment, n, i.max_units))).toBe(canonical(expected));
  });
});
