import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, userEvent, within } from 'storybook/test';
import { QuoteForm } from './quote-form';
import { Mocked, type Routes } from './story-mocks';

// Dealer quote builder states (P3.D). Variant rows are real catalogue rows (Maruti Suzuki Swift, Mumbai, catalogue
// ex-showroom). No visual baselines are added here: the nightly visual job records them.
const meta: Meta = { title: 'Dealer quote builder', parameters: { layout: 'padded', nextjs: { appDirectory: true } } };
export default meta;
type Story = StoryObj;

const POOL = '01a17000-0000-7000-8000-000000000001';
const VARIANTS = [
  { id: '96a9e043-f401-5b03-b82a-52e617913ce5', code: 'swift-lxi-mt', name: 'LXi MT', fuel: 'PETROL', transmission: 'MT', engine_cc: 1197, catalogue_ex_showroom_paise: 58390000, catalogue_evidence: 'CATALOGUE', city_confirmed: true },
  { id: '709324c3-8db5-5baf-a801-575995a81409', code: 'swift-vxi-amt', name: 'VXi AMT', fuel: 'PETROL', transmission: 'AMT', engine_cc: 1197, catalogue_ex_showroom_paise: 71290000, catalogue_evidence: 'CATALOGUE', city_confirmed: true },
];
const inHours = (h: number) => new Date(Date.now() + h * 36e5).toISOString();
const request = (state: 'OPEN' | 'CLOSED', kind: 'SEALED' | 'FINAL' = 'SEALED') => ({
  round_id: '01a17000-0000-7000-8000-0000000000r1', pool_id: POOL, round_set: 1, round_no: kind === 'SEALED' ? 1 : 2, kind, deadline: inHours(state === 'OPEN' ? 20 : -1),
  state, state_code: 'MH', variants: VARIANTS, committed_count: 6, quote_id: 'q1', quote_state: null, quote_version: null, server_time: new Date().toISOString(), ruleset_version: 'rs-2026-10',
});
const draftOf = (discounts: [number, number]) => ({
  variants: [{ variant_id: VARIANTS[0].id, ex_showroom_paise: 58390000, registration_paise: null, insurance: null, insurance_addons: [], fees: [], extended_warranty: null, other_charges: [],
    accessories: [{ code: 'MUDFLAPS', label: 'Mudflaps', amount_paise: 200000, basis: 'FIXED', required: true, selected: false, evidence: 'DEALER_CONFIRMED' }],
    stock: { units: 5, colours: ['White'], delivery_date: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10) } }],
  tiers: [{ min: 1, max: 4, discount_paise: discounts[0] }, { min: 5, max: null, discount_paise: discounts[1] }],
  min_fulfilment: 1, max_units: null, valid_until: new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10),
});
const quote = (state: string, draft: unknown, version = 1) => ({ id: 'q1', pool_id: POOL, state, version: version + 1, draft, current_version: state === 'DRAFT' ? 0 : version, submitted_at: state === 'DRAFT' ? null : new Date().toISOString() });
const ok = (body: unknown) => ({ status: 200, body });

const builder = (routes: Routes) => {
  const Builder = () => <Mocked routes={routes}><QuoteForm quoteId="q1" /></Mocked>;
  return Builder;
};
const base = (q: unknown, req = request('OPEN'), ranks: unknown[] = []): Routes => ({
  '^GET /api/dealer/quotes/q1$': [ok(q)],
  '^GET /api/dealer/inbox': [ok({ requests: [req] })],
  '^GET /api/dealer/quotes/q1/rank': [ok({ quote_id: 'q1', ranks })],
});

export const NewDraft: Story = { render: builder(base(quote('DRAFT', {}))) };

export const NeedsFixing: Story = {
  render: builder(base(quote('DRAFT', {}))),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    const from = await c.findByLabelText('Tier 2 from');
    await userEvent.clear(from);
    await userEvent.type(from, '7');
    await expect(await c.findByText(/Fix before submitting/)).toBeVisible();
    await expect(c.getByRole('button', { name: 'Submit sealed quote' })).toBeDisabled();
  },
};

export const Submitted: Story = { render: builder(base(quote('SUBMITTED', draftOf([1000000, 2500000])))) };

export const RankedInTheFinalRound: Story = {
  render: builder(base(quote('REVISED', draftOf([1500000, 2500000]), 2), request('OPEN', 'FINAL'),
    [{ variant_id: VARIANTS[0].id, rank: 2, of_n: 4, rule_version: 'ranking-rule.md#2', computed_at: new Date().toISOString() }])),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(await c.findByText('2nd of 4 for LXi MT')).toBeVisible();
    await expect(c.getByText(/Ranking rule:/)).toBeVisible();
  },
};

export const RoundClosed: Story = { render: builder(base(quote('SUBMITTED', draftOf([1000000, 2500000])), request('CLOSED'))) };
