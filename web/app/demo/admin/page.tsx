import { getTranslations } from 'next-intl/server';
import { AnalyticsFigures, type Analytics } from '@/components/analytics-view';

// P6.B: the public "view as admin". Fixed, made-up figures rendered by the real component; nothing here calls an admin API.
const SYNTHETIC: Analytics = {
  simulated: true,
  fill: [
    { week: '2026-W41', created: 14, filled: 9, median_hours_to_fill: 31 },
    { week: '2026-W42', created: 18, filled: 13, median_hours_to_fill: 26 },
    { week: '2026-W43', created: 21, filled: 16, median_hours_to_fill: 22 },
  ],
  dealer_response: { rounds: 38, invited: 152, responded: 117 },
  pools_with_two_valid_quotes: { quote_open: 38, two_or_more: 33 },
  acceptance_to_confirmation: { orders: 41, confirmed: 36 },
  confirmation_to_delivery: { closed_bookings: 29, delivered: 27 },
  cancellation: { issued: 36, cancelled: 4, by_reason: { buyer: 2, dealer: 1, stock: 1 } },
  void_rate: { accepted_quotes: 33, void_quotes: 1 },
  refund_latency_hours: { n: 7, p50: 2.5, p95: 6.1 },
  dispute_resolution_days: { n: 3, p50: 2.0, p95: 4.0 },
  savings: {
    dealer_stated_discount: { n: 33, p25_paise: 1200000, median_paise: 1850000, p75_paise: 2400000, items: [] },
    vs_catalogue_estimate: { n: 33, p25_paise: 900000, median_paise: 1500000, p75_paise: 2100000, items: [] },
    vs_best_other_quote: { n: 29, p25_paise: 150000, median_paise: 400000, p75_paise: 750000, items: [] },
    realised: { n: 27, p25_paise: 800000, median_paise: 1400000, p75_paise: 2000000, items: [] },
  },
};

export default async function DemoAdminPage() {
  const t = await getTranslations('demo');
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('adminTitle')}</h1>
      <p className="max-w-2xl text-muted">{t('adminNote')}</p>
      <AnalyticsFigures a={SYNTHETIC} />
    </div>
  );
}
