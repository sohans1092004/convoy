// Pure logic of the dealer quote builder (P3.D), unit-tested in quote-builder.test.ts. The server re-validates and
// re-prices everything on submit; this is the dealer's live preview and early warning only.

export interface TierRow { min: string; max: string; discount: string }
export interface Accessory { label: string; amount: string; required: boolean }
export interface Issue { field: string; message: string }

const num = (s: string) => (s.trim() === '' ? null : Number(s.replace(/,/g, '')));
export const paise = (rupeesText: string) => { const n = num(rupeesText); return n === null || Number.isNaN(n) ? null : Math.round(n * 100); };

/** Tier ladder checks mirroring the server's (contiguous from 1, last open-ended, discount never shrinks). */
export function tierIssues(tiers: TierRow[], minF: string, maxUnits: string): Issue[] {
  const out: Issue[] = [];
  if (tiers.length === 0) return [{ field: 'tiers', message: 'Add at least one tier.' }];
  let expected = 1;
  let lastDiscount = -1;
  tiers.forEach((t, i) => {
    const min = num(t.min), max = num(t.max), d = paise(t.discount);
    if (min === null || !Number.isInteger(min) || min < 1) out.push({ field: `tier.${i}.min`, message: `Tier ${i + 1}: "from" must be a whole number of buyers.` });
    else if (min !== expected) out.push({ field: `tier.${i}.min`, message: `Tier ${i + 1} should start at ${expected} so the ranges are contiguous.` });
    const last = i === tiers.length - 1;
    if (last && max !== null) out.push({ field: `tier.${i}.max`, message: 'The last tier has no upper limit: leave "to" empty.' });
    if (!last && (max === null || min === null || max < min)) out.push({ field: `tier.${i}.max`, message: `Tier ${i + 1}: "to" must be at least "from".` });
    if (d === null || d < 0) out.push({ field: `tier.${i}.discount`, message: `Tier ${i + 1}: enter a discount (0 or more).` });
    else if (d < lastDiscount) out.push({ field: `tier.${i}.discount`, message: `Tier ${i + 1}: the discount cannot be smaller than tier ${i}'s (the price can only fall).` });
    if (d !== null) lastDiscount = Math.max(lastDiscount, d);
    expected = (max ?? 0) + 1;
  });
  const f = num(minF), mu = num(maxUnits);
  if (f === null || !Number.isInteger(f) || f < 1) out.push({ field: 'min_fulfilment', message: 'Minimum confirmed buyers must be 1 or more.' });
  if (mu !== null && f !== null && mu < f) out.push({ field: 'max_units', message: 'Maximum units cannot be below the minimum confirmed buyers.' });
  return out;
}

export interface LineInput { name: string; ex: string; units: string; colour: string; delivery: string; insMode: 'estimate' | 'dealer'; od: string; tp: string; gst: string; accessories: Accessory[] }

/** Missing or inconsistent items on one variant line. */
export function lineIssues(l: LineInput, i: number): Issue[] {
  const out: Issue[] = [];
  const ex = paise(l.ex);
  if (ex === null || ex <= 0) out.push({ field: `line.${i}.ex`, message: `${l.name}: enter the ex-showroom price.` });
  const units = num(l.units);
  if (units === null || !Number.isInteger(units) || units < 0) out.push({ field: `line.${i}.units`, message: `${l.name}: units in stock must be 0 or more.` });
  if ((units ?? 0) > 0 && l.colour.trim() === '') out.push({ field: `line.${i}.colour`, message: `${l.name}: name the colour of the stock you hold.` });
  if (l.insMode === 'dealer' && [l.od, l.tp, l.gst].some((x) => paise(x) === null)) out.push({ field: `line.${i}.insurance`, message: `${l.name}: fill in all three insurance figures, or use the baseline estimate.` });
  l.accessories.forEach((a, k) => {
    if (a.label.trim() === '' || (paise(a.amount) ?? -1) < 0) out.push({ field: `line.${i}.acc.${k}`, message: `${l.name}: accessory ${k + 1} needs a name and an amount.` });
  });
  return out;
}

export function validityIssues(validUntil: string, today: string): Issue[] {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(validUntil)) return [{ field: 'valid_until', message: 'Give the date until which this quote is valid.' }];
  return validUntil < today ? [{ field: 'valid_until', message: 'The validity date is in the past.' }] : [];
}

/** Index of the tier holding n confirmed buyers (null if none). */
export function tierFor(tiers: TierRow[], n: number): number | null {
  const i = tiers.findIndex((t) => { const min = num(t.min), max = num(t.max); return min !== null && n >= min && (max === null || n <= max); });
  return i < 0 ? null : i;
}

/**
 * "What if 3 / 7 / 12 buyers confirm": the price each buyer pays at that count, given per-tier cash payable. Below
 * min_fulfilment the quote is void (null + reason); above max_units the dealer cannot serve everyone.
 */
export function whatIf(tiers: TierRow[], cashPerTier: (number | null)[], minF: number, maxUnits: number | null, counts = [3, 7, 12]) {
  return counts.map((n) => {
    if (n < minF) return { n, cash: null, note: `below your minimum of ${minF}: the quote would not go ahead` };
    if (maxUnits !== null && n > maxUnits) return { n, cash: null, note: `above your maximum of ${maxUnits} units` };
    const t = tierFor(tiers, n);
    return t === null ? { n, cash: null, note: 'no tier covers this count' } : { n, cash: cashPerTier[t] ?? null, note: `tier ${t + 1}` };
  });
}

/** True when more confirmed buyers never means a higher price (the published rule). */
export const priceOnlyFalls = (cashPerTier: (number | null)[]) => cashPerTier.every((c, i) => i === 0 || c === null || cashPerTier[i - 1] === null || c <= (cashPerTier[i - 1] as number));

/** Changed leaves between two drafts, like the server's audit diff: [{path, from, to}]. */
export function diff(before: unknown, after: unknown, path = ''): { path: string; from: unknown; to: unknown }[] {
  const isObj = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x);
  if (isObj(before) && isObj(after)) {
    return [...new Set([...Object.keys(before), ...Object.keys(after)])].sort().flatMap((k) => diff(before[k], after[k], path ? `${path}.${k}` : k));
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    return Array.from({ length: Math.max(before.length, after.length) }, (_, i) => diff(before[i], after[i], `${path}[${i}]`)).flat();
  }
  return JSON.stringify(before ?? null) === JSON.stringify(after ?? null) ? [] : [{ path, from: before ?? null, to: after ?? null }];
}

/** "2nd of 4". */
export function ordinal(n: number) {
  const s = n % 100 >= 11 && n % 100 <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th';
  return `${n}${s}`;
}
