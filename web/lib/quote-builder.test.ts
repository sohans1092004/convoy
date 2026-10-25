import { describe, expect, it } from 'vitest';
import { diff, lineIssues, ordinal, priceOnlyFalls, tierIssues, validityIssues, whatIf, type LineInput } from './quote-builder';

const ladder = [{ min: '1', max: '4', discount: '10000' }, { min: '5', max: '9', discount: '20000' }, { min: '10', max: '', discount: '30000' }];

describe('tier ladder', () => {
  it('accepts a contiguous, open-ended, non-shrinking ladder', () => {
    expect(tierIssues(ladder, '3', '')).toEqual([]);
  });
  it('flags gaps, a capped last tier, a shrinking discount and max below min', () => {
    const bad = [{ min: '1', max: '4', discount: '20000' }, { min: '6', max: '9', discount: '10000' }];
    const fields = tierIssues(bad, '5', '3').map((i) => i.field);
    expect(fields).toEqual(expect.arrayContaining(['tier.1.min', 'tier.1.max', 'tier.1.discount', 'max_units']));
  });
});

describe('what if 3 / 7 / 12 buyers confirm', () => {
  it('prices each count at its tier and explains counts outside the quote', () => {
    const rows = whatIf(ladder, [700000, 690000, 680000], 5, 11);
    expect(rows).toEqual([
      { n: 3, cash: null, note: 'below your minimum of 5: the quote would not go ahead' },
      { n: 7, cash: 690000, note: 'tier 2' },
      { n: 12, cash: null, note: 'above your maximum of 11 units' },
    ]);
  });
  it('the price can only fall as more buyers confirm', () => {
    expect(priceOnlyFalls([700000, 690000, 680000])).toBe(true);
    expect(priceOnlyFalls([700000, 710000])).toBe(false);
    expect(priceOnlyFalls([700000, null, 680000])).toBe(true); // an invalid tier is reported elsewhere
  });
});

describe('line and validity checks', () => {
  const line: LineInput = { name: 'LXi MT', ex: '583900', units: '3', colour: 'White', delivery: '2026-11-20', insMode: 'estimate', od: '', tp: '', gst: '', accessories: [] };
  it('a complete line has no issues', () => expect(lineIssues(line, 0)).toEqual([]));
  it('flags a missing price, colour, partial insurance and a nameless accessory', () => {
    const fields = lineIssues({ ...line, ex: '', colour: ' ', insMode: 'dealer', od: '1000', accessories: [{ label: '', amount: '500', required: true }] }, 0).map((i) => i.field);
    expect(fields).toEqual(['line.0.ex', 'line.0.colour', 'line.0.insurance', 'line.0.acc.0']);
  });
  it('validity must be a date, today or later', () => {
    expect(validityIssues('2026-11-01', '2026-10-25')).toEqual([]);
    expect(validityIssues('2026-10-01', '2026-10-25')[0].message).toContain('past');
    expect(validityIssues('', '2026-10-25')).toHaveLength(1);
  });
});

describe('revision diff and rank label', () => {
  it('lists only the changed leaves', () => {
    expect(diff({ tiers: [{ discount_paise: 1 }], min_fulfilment: 1 }, { tiers: [{ discount_paise: 2 }], min_fulfilment: 1 }))
      .toEqual([{ path: 'tiers[0].discount_paise', from: 1, to: 2 }]);
  });
  it('ordinals', () => expect([1, 2, 3, 4, 11, 12, 13, 21, 22].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd']));
});
