import { getRequestConfig } from 'next-intl/server';
import { cookies } from 'next/headers';

// Locales: en (default) and hi (buyer journey; machine-drafted, see messages/README.md). The choice is the
// NEXT_LOCALE cookie (set by the header switcher). A key missing in hi falls back to en, never to a blank.
export const LOCALES = ['en', 'hi'] as const;
export type Locale = (typeof LOCALES)[number];

type Messages = { [k: string]: string | Messages };
function merge(base: Messages, over: Messages): Messages {
  const out: Messages = { ...base };
  for (const [k, v] of Object.entries(over)) out[k] = typeof v === 'object' && typeof base[k] === 'object' ? merge(base[k] as Messages, v) : v;
  return out;
}

export default getRequestConfig(async () => {
  const pick = (await cookies()).get('NEXT_LOCALE')?.value;
  const locale: Locale = pick === 'hi' ? 'hi' : 'en';
  const en = (await import('../messages/en.json')).default as Messages;
  const messages = locale === 'hi' ? merge(en, (await import('../messages/hi.json')).default as Messages) : en;
  return {
    locale,
    timeZone: 'Asia/Kolkata',
    messages,
    formats: {
      number: { inr: { style: 'currency', currency: 'INR', maximumFractionDigits: 0 } },
      dateTime: { deadline: { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' } },
    },
  };
});
