// Money is integer paise everywhere; display converts once, here, with en-IN (lakh) grouping.
const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const inrPaise = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function rupees(paise: number, exact = false): string {
  if (!Number.isSafeInteger(paise)) throw new RangeError('paise must be a safe integer');
  return paise % 100 === 0 || !exact ? inr.format(Math.round(paise / 100)) : inrPaise.format(paise / 100);
}

/** Times are stored in UTC and shown in IST. */
export function ist(iso: string | null | undefined, withSeconds = false): string {
  if (!iso) return '';
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}), hour12: true,
  }).format(new Date(iso)) + ' IST';
}

export type Evidence = import('./api').Schemas['catalog']['Line']['evidence'];

export const EVIDENCE_LABEL: Record<Evidence, string> = {
  DEALER_CONFIRMED: 'Dealer-confirmed',
  CATALOGUE_ESTIMATE: 'Catalogue estimate',
  UNVERIFIED: 'Unverified',
};

export const LINE_LABEL: Record<string, string> = {
  EX_SHOWROOM: 'Ex-showroom price',
  DISCOUNT: 'Dealer discount',
  REGISTRATION: 'Registration (dealer figure)',
  ROAD_TAX: 'Road tax',
  ROAD_TAX_CESS: 'Road tax cess',
  REGISTRATION_FEE: 'Registration fee',
  SMART_CARD_FEE: 'Smart card fee',
  INSURANCE_OD: 'Insurance: own damage, year 1',
  INSURANCE_TP: 'Insurance: third party, 3 years',
  INSURANCE_GST: 'GST on insurance',
  TCS: 'TCS 1% (creditable against income tax)',
  EXTENDED_WARRANTY: 'Extended warranty',
};

export function lineLabel(code: string): string {
  if (LINE_LABEL[code]) return LINE_LABEL[code];
  const [kind, rest] = code.split(':');
  const nice = (rest ?? '').replace(/_/g, ' ').toLowerCase();
  return { ACCESSORY: 'Accessory', FEE: 'Fee', CHARGE: 'Other charge', INSURANCE_ADDON: 'Insurance add-on' }[kind] + (nice ? `: ${nice}` : '');
}
