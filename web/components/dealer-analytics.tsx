'use client';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Card, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call } from '@/lib/api';
import { useSnapshot } from '@/lib/use-snapshot';

type Mine = {
  simulated: boolean;
  response: { invited: number; responded: number };
  win: { final_quotes: number; won: number };
  reliability_score_percent: number | null;
};

/** E058 (dealer_admin): my dealership only; the server takes the dealership from the session. */
export function DealerAnalytics() {
  const t = useTranslations('dealerAnalytics');
  const snap = useSnapshot(() => call(api.GET('/dealer/analytics')), [], 60_000);
  const a = snap.data as Mine | undefined; // free-form object (Map) in the OpenAPI document
  if (!a) return <Skeleton className="h-32" label={t('loading')} />;
  const sim = a.simulated ? <Badge tone="warning">{t('simulated')}</Badge> : null;
  const rate = (num: number, den: number) => (den === 0 ? '—' : `${((100 * num) / den).toFixed(1)}%`);
  const row = (label: string, value: string, detail: string) => (
    <Card className="flex flex-wrap items-center gap-2">
      <CardTitle as="h2" className="text-base">{label}</CardTitle>
      <span className="tabular text-lg font-semibold">{value}</span>
      <span className="text-sm text-muted">{detail}</span>
      {sim}
    </Card>
  );
  return (
    <div className="flex flex-col gap-3">
      <h1 className="text-xl font-bold">{t('title')}</h1>
      {row(t('response'), rate(a.response.responded, a.response.invited), t('of', { num: a.response.responded, den: a.response.invited }))}
      {row(t('win'), rate(a.win.won, a.win.final_quotes), t('of', { num: a.win.won, den: a.win.final_quotes }))}
      {row(t('reliability'), a.reliability_score_percent == null ? '—' : `${a.reliability_score_percent}%`, t('reliabilityNote'))}
    </div>
  );
}
