// What the compare screen works out from the offers (pure, so it is unit-tested). All figures come from the price engine; nothing is re-priced here.
import type { Schemas } from '@/lib/api';
import type { Evidence } from '@/lib/format';

type Offer = Schemas['quote']['Offer'];
type Variant = Offer['variants'][number];
type Line = Variant['ceiling']['output']['lines'][number];
export type TierView = Schemas['quote']['TierView'];
export interface KeyLine { key: string; amount_paise: number; evidence: Evidence; flags: string[] }

const GROUP: Record<string, string> = {
  ROAD_TAX_CESS: 'ROAD_TAX', REGISTRATION_FEE: 'REGISTRATION', SMART_CARD_FEE: 'REGISTRATION',
  INSURANCE_OD: 'INSURANCE', INSURANCE_TP: 'INSURANCE', INSURANCE_GST: 'INSURANCE',
};
const ORDER = ['EX_SHOWROOM', 'DISCOUNT', 'ROAD_TAX', 'REGISTRATION', 'INSURANCE', 'TCS'];
const STRENGTH: Evidence[] = ['UNVERIFIED', 'CATALOGUE_ESTIMATE', 'DEALER_CONFIRMED'];
const position = (key: string) => (ORDER.indexOf(key) + 1 || ORDER.length + 1);

/** The lines in cash payable, grouped for reading across dealers. A group shows its weakest evidence, as the pricing model does for computed lines. Non-zero lines always sum to cash payable. */
export function keyLines(lines: Line[]): KeyLine[] {
  const rows = new Map<string, KeyLine>();
  for (const l of lines.filter((x) => x.in_cash_payable && x.amount_paise !== 0)) {
    const key = GROUP[l.code] ?? l.code;
    const row = rows.get(key);
    if (!row) rows.set(key, { key, amount_paise: l.amount_paise, evidence: l.evidence, flags: l.flags });
    else {
      row.amount_paise += l.amount_paise;
      row.flags = [...row.flags, ...l.flags];
      if (STRENGTH.indexOf(l.evidence) < STRENGTH.indexOf(row.evidence)) row.evidence = l.evidence;
    }
  }
  return [...rows.values()].sort((a, b) => position(a.key) - position(b.key));
}

/** The variant an offer currently shows: the buyer's choice, else the one accepted, else the first priced. */
export const shownVariant = (offer: Offer, chosenId: string | null | undefined, acceptedId: string | null | undefined): Variant =>
  offer.variants.find((v) => v.variant_id === (chosenId ?? acceptedId)) ?? offer.variants[0];

/** Lowest ceiling first (ranking-rule.md §4); ties as in §2: earliest delivery date (none last), then earliest committed. */
export function sortOffers(offers: Offer[], shown: (o: Offer) => Variant): Offer[] {
  const date = (o: Offer) => shown(o).stock.delivery_date ?? '9999';
  return [...offers].sort((a, b) => shown(a).ceiling.cash_payable_paise - shown(b).ceiling.cash_payable_paise
    || date(a).localeCompare(date(b)) || a.committed_at.localeCompare(b.committed_at) || a.quote_id.localeCompare(b.quote_id));
}

/** Rows of the matrix: every key line any shown offer has, with whether the amounts differ across offers. */
export function matrixRows(lists: KeyLine[][]): { key: string; differs: boolean }[] {
  const keys = [...new Set(lists.flat().map((r) => r.key))].sort((a, b) => position(a) - position(b));
  return keys.map((key) => ({ key, differs: new Set(lists.map((l) => l.find((r) => r.key === key)?.amount_paise ?? 0)).size > 1 }));
}

/** The price the next confirmed-buyer range would bring, if there is one below the ceiling. */
export function nextTier(tiers: TierView[]): TierView | null {
  const i = tiers.findIndex((t) => t.is_ceiling);
  return i >= 0 ? tiers[i + 1] ?? null : null;
}
