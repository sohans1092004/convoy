import { getRequestConfig } from 'next-intl/server';

// English only in P1 (Hindi strings come later); numbers and money use en-IN (lakh grouping).
export default getRequestConfig(async () => ({
  locale: 'en',
  timeZone: 'Asia/Kolkata',
  messages: (await import('../messages/en.json')).default,
  formats: {
    number: { inr: { style: 'currency', currency: 'INR', maximumFractionDigits: 0 } },
    dateTime: { deadline: { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' } },
  },
}));
