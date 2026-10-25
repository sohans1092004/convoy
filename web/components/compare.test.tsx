import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Breakdown, type EngineOutput } from './breakdown';
import { stockConfidence, TierLadder } from './compare-view';
import { rupees } from '@/lib/format';

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
  const html = renderToStaticMarkup(<Breakdown output={output} caption="Breakdown at the ceiling" />);
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

describe('tier ladder', () => {
  const tiers = [
    { min: 1, max: 4, cash_payable_paise: 66347500, is_ceiling: false },
    { min: 5, max: 9, cash_payable_paise: 65347500, is_ceiling: true },
    { min: 10, max: null, cash_payable_paise: 64347500, is_ceiling: false },
  ];
  const html = renderToStaticMarkup(<TierLadder tiers={tiers} minFulfilment={5} />);
  it('states the rule, marks the ceiling and the void range, and lists every price', () => {
    expect(html).toContain('Price can only fall');
    expect(html).toContain('5–9 confirmed');
    expect(html).toContain('— your ceiling');
    expect(html).toContain('(offer void below the minimum)');
    expect(html).toContain('10+ confirmed');
  });
});

describe('stock confidence', () => {
  it('compares the stock claim with the committed buyers and says it is the dealer\'s statement', () => {
    expect(stockConfidence({ units: 6, colours: ['White'], delivery_date: '2026-11-20' }, 4)).toMatchObject({ level: 'enough' });
    expect(stockConfidence({ units: 2, colours: ['White'], delivery_date: null }, 4).text).toContain('some cars come later');
    expect(stockConfidence({ units: 0, colours: [], delivery_date: null }, 4).level).toBe('none');
    expect(stockConfidence({ units: 6, colours: ['White'], delivery_date: '2026-11-20' }, 4).text).toContain("dealer's statement, delivery from 2026-11-20");
  });
});
