import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Breakdown, type EngineOutput } from './breakdown';
import { addonFlags, OfferCard, StockNote, stockConfidence, TierLadder } from './compare-view';
import { Countdown, deadlinePhase } from './ui/countdown';
import { keyLines, matrixRows, nextTier, sortOffers } from '@/lib/compare';
import { rupees } from '@/lib/format';
import { NextIntlClientProvider } from 'next-intl';
import type { ReactNode } from 'react';
import messages from '../messages/en.json';

const render = (ui: ReactNode) => renderToStaticMarkup(<NextIntlClientProvider locale="en" messages={messages} timeZone="Asia/Kolkata">{ui}</NextIntlClientProvider>);

// Component tests (P3.E): rendered to static markup, so they check what reaches the page without a browser.
const output = {
  status: 'OK', cash_payable_paise: 66347500, estimated_net_cost_paise: 65763600,
  lines: [
    { code: 'EX_SHOWROOM', amount_paise: 58390000, evidence: 'DEALER_CONFIRMED', in_cash_payable: true, flags: [] },
    { code: 'ROAD_TAX', amount_paise: 6072560, evidence: 'CATALOGUE_ESTIMATE', in_cash_payable: true, flags: [] },
    { code: 'SMART_CARD', amount_paise: 20000, evidence: 'UNVERIFIED', in_cash_payable: true, flags: [] },
    { code: 'ACCESSORY:SEAT_COVERS', amount_paise: 1200000, evidence: 'DEALER_CONFIRMED', in_cash_payable: false, flags: ['OPTIONAL'] },
  ],
} as unknown as EngineOutput;

describe('price breakdown', () => {
  const html = render(<Breakdown output={output} caption="Breakdown at the ceiling" />);
  it('has a stacked mobile list (no table) with every line, amount and evidence label', () => {
    const mobile = html.slice(0, html.indexOf('</section>'));
    expect(mobile).not.toContain('<table');
    for (const l of output.lines) expect(mobile).toContain(rupees(l.amount_paise).replace(/&/g, '&amp;'));
    expect(mobile.match(/Dealer-confirmed/g)?.length).toBe(2);
    expect(mobile).toContain('Catalogue estimate');
    expect(mobile).toContain('Unverified');
    expect(mobile).toContain('Optional (not included above)');
  });
  it('keeps the table for wider screens, hidden below sm', () => {
    expect(html).toMatch(/class="hidden [^"]*sm:block"/);
    expect(html).toMatch(/<section class="[^"]*sm:hidden"/);
  });
});

describe('compare helpers (rate card)', () => {
  const line = (code: string, amount_paise: number, evidence = 'DEALER_CONFIRMED') => ({ code, amount_paise, evidence, in_cash_payable: true, flags: [] });
  const lines = [line('EX_SHOWROOM', 58390000), line('DISCOUNT', -1460000), line('ROAD_TAX', 6000000, 'CATALOGUE_ESTIMATE'), line('REGISTRATION_FEE', 60000, 'CATALOGUE_ESTIMATE'), line('SMART_CARD_FEE', 20000, 'UNVERIFIED'),
    line('INSURANCE_OD', 1000000, 'CATALOGUE_ESTIMATE'), line('INSURANCE_TP', 1000000, 'CATALOGUE_ESTIMATE'), line('ACCESSORY:MUDFLAPS', 600000), { ...line('EXTENDED_WARRANTY', 99), in_cash_payable: false }] as never[];
  it('groups lines, keeps the weakest evidence, and sums to the same total', () => {
    const rows = keyLines(lines);
    expect(rows.map((r) => r.key)).toEqual(['EX_SHOWROOM', 'DISCOUNT', 'ROAD_TAX', 'REGISTRATION', 'INSURANCE', 'ACCESSORY:MUDFLAPS']);
    expect(rows.find((r) => r.key === 'REGISTRATION')).toMatchObject({ amount_paise: 80000, evidence: 'UNVERIFIED' });
    expect(rows.reduce((a, r) => a + r.amount_paise, 0)).toBe(58390000 - 1460000 + 6000000 + 80000 + 2000000 + 600000);
  });
  it('sorts by ceiling, breaking ties by delivery date then commit time', () => {
    const o = (id: string, cash: number, date: string | null, at = '2026-11-02') => ({ quote_id: id, committed_at: at, variants: [{ variant_id: 'v', ceiling: { cash_payable_paise: cash }, stock: { delivery_date: date } }] }) as never;
    const ids = sortOffers([o('c', 200, null), o('b', 100, null), o('a', 100, '2026-11-20')], (x) => x.variants[0]).map((x) => x.quote_id);
    expect(ids).toEqual(['a', 'b', 'c']);
  });
  it('marks a row as differing when an offer lacks it', () => {
    expect(matrixRows([[{ key: 'A', amount_paise: 1, evidence: 'UNVERIFIED', flags: [] }], []])).toEqual([{ key: 'A', differs: true }]);
  });
  it('finds the rung after the ceiling, or none at the end', () => {
    const t = (min: number, is_ceiling = false) => ({ min, max: null, cash_payable_paise: min, is_ceiling });
    expect(nextTier([t(1, true), t(5)])?.min).toBe(5);
    expect(nextTier([t(1), t(5, true)])).toBeNull();
    expect(nextTier([t(1)])).toBeNull();
  });
  it('states a deadline without its seconds, and changes phase only at the last minute and the close', () => {
    const at = (iso: string) => () => Date.parse(iso);
    const html = render(<Countdown label="Decide by" deadline="2026-11-02T08:00:00Z" now={at('2026-11-02T07:00:00Z')} />);
    expect(html).toContain('2 Nov');
    expect(html).toContain('Time now');
    expect(html).not.toContain('database');
    expect([deadlinePhase(61_000), deadlinePhase(60_000), deadlinePhase(0)]).toEqual(['open', 'warning', 'closed']);
  });
  it('states the minimum, not the alarm, at a count of 0', () => {
    const variant = { variant_id: 'v1', variant_name: 'LXi MT', projection: { label: 'If the count stays at 0', count: 0, guaranteed: false, cash_payable_paise: null, note: 'would not go ahead' },
      tiers: [], stock: { units: 1, colours: [], delivery_date: null }, ceiling: { cash_payable_paise: 100, output } };
    const off = { quote_id: 'q', dealership_id: 'd', state: 'FINAL', version_no: 1, committed_at: '2026-11-02T07:55:52Z', min_fulfilment: 3, variants: [variant], clarifications: [] } as never;
    const html = render(<OfferCard offer={off} poolId="p" acceptedVariantId={null} canAccept committed={1} flagged={[]} onAccepted={() => {}} />);
    expect(html).toContain('Minimum 3 confirmed buyers');
    expect(html).not.toContain('would not go ahead');
  });
});

describe('tier ladder', () => {
  const tiers = [
    { min: 1, max: 4, cash_payable_paise: 66347500, is_ceiling: false },
    { min: 5, max: 9, cash_payable_paise: 65347500, is_ceiling: true },
    { min: 10, max: null, cash_payable_paise: 64347500, is_ceiling: false },
  ];
  const html = render(<TierLadder tiers={tiers} minFulfilment={5} />);
  it('states the rule, marks the ceiling and the void range, and lists every price', () => {
    expect(html).toContain('Price can only fall');
    expect(html).toContain('5–9 confirmed');
    expect(html).toContain('— your ceiling');
    expect(html).toContain('(offer void below the minimum)');
    expect(html).toContain('10+ confirmed');
  });
});

describe('stock confidence', () => {
  it('compares the stock claim with the committed buyers', () => {
    expect(stockConfidence({ units: 6, colours: ['White'], delivery_date: '2026-11-20' }, 4)).toBe('enough');
    expect(stockConfidence({ units: 2, colours: ['White'], delivery_date: null }, 4)).toBe('short');
    expect(stockConfidence({ units: 0, colours: [], delivery_date: null }, 4)).toBe('none');
  });
  it('says it is the dealer\'s statement (translated wording)', () => {
    const html = render(<StockNote stock={{ units: 2, colours: ['White'], delivery_date: '2026-11-20' }} committed={4} />);
    expect(html).toContain('some cars come later');
    expect(html).toContain('delivery from 2026-11-20');
  });
});

describe('dealer-required add-on flag', () => {
  const offer = (id: string, lines: unknown[]) => ({ quote_id: id, variants: [{ ceiling: { output: { lines } } }] }) as never;
  const addon = { code: 'ACCESSORY:MUDFLAPS', amount_paise: 600000, evidence: 'DEALER_CONFIRMED', in_cash_payable: true, flags: ['DEALER_REQUIRED'] };
  it('flags an add-on only one offer requires, not one every offer requires', () => {
    expect(Object.keys(addonFlags([offer('a', [addon]), offer('b', []), offer('c', [])]))).toEqual(['a']);
    expect(addonFlags([offer('a', [addon]), offer('b', [addon])])).toEqual({});
  });
});

describe('offer variants (U.1)', () => {
  const variant = (id: string, name: string, cash: number, addon = false) => ({
    variant_id: id, variant_code: id, variant_name: name, projection: { label: 'If the count stays at 1', count: 1, guaranteed: false, cash_payable_paise: cash, note: 'Projection, not guaranteed.' },
    tiers: [{ min: 1, max: null, cash_payable_paise: cash, is_ceiling: true }], stock: { units: 3, colours: [], delivery_date: null, evidence: 'DEALER_CONFIRMED' },
    ceiling: { tier_index: 0, cash_payable_paise: cash, snapshot_sha256: 'x', output: { ...output, lines: addon ? [{ code: 'ACCESSORY:MUDFLAPS', amount_paise: 600000, evidence: 'DEALER_CONFIRMED', in_cash_payable: true, flags: ['DEALER_REQUIRED'] }] : output.lines } },
  });
  const offer = (variants: ReturnType<typeof variant>[], id = 'q1') => ({ quote_id: id, dealership_id: 'd1', state: 'FINAL', version_no: 1, committed_at: '2026-11-02T07:55:52Z', min_fulfilment: 1, max_units: null, snapshot_sha256: 'x', variants, clarifications: [] }) as unknown as Parameters<typeof addonFlags>[0][number];
  const card = (o: ReturnType<typeof offer>, acceptedVariantId: string | null = null) => render(<OfferCard offer={o} poolId="p1" acceptedVariantId={acceptedVariantId} canAccept committed={1} flagged={[]} onAccepted={() => {}} />);

  it('names a single variant without offering a choice', () => {
    const html = card(offer([variant('v1', 'LXi MT', 58390000)]));
    expect(html).toContain('Variant: LXi MT');
    expect(html).not.toContain('type="radio"');
  });
  it('offers every priced variant, and the figures follow the one selected', () => {
    const html = card(offer([variant('v1', 'LXi MT', 58390000), variant('v2', 'VXi AMT', 71290000)]), 'v2');
    expect(html).toContain('Choose a variant');
    expect(html.match(/type="radio"/g)?.length).toBe(2);
    expect(html).toContain('LXi MT');
    expect(html).toContain(rupees(71290000).replace(/&/g, '&amp;'));
    expect(html).toMatch(/<button[^>]*disabled[^>]*>.*Accepted/s); // v2 is the accepted one, so its button is spent
  });
  it('lets a buyer switch away from the accepted variant', () => {
    const html = card(offer([variant('v1', 'LXi MT', 58390000), variant('v2', 'VXi AMT', 71290000)]), 'v9');
    expect(html).toContain('You accepted another variant');
  });
  it('lists an add-on once when an offer repeats it on several variants', () => {
    const flags = addonFlags([offer([variant('v1', 'LXi MT', 1, true), variant('v2', 'VXi AMT', 2, true)]), offer([variant('v3', 'LXi MT', 3)], 'q2')]);
    expect(flags.q1).toHaveLength(1);
  });
});
