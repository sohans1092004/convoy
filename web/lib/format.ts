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
  // ponytail: reads the page language rather than threading a locale through every caller; on the server it stays en-IN.
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('hi') ? 'hi-IN' : 'en-IN';
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}), hour12: true,
  }).format(new Date(iso)) + ' IST';
}

export type Evidence = import('./api').Schemas['catalog']['Line']['evidence'];
