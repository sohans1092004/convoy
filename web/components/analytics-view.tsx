'use client';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Card, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call } from '@/lib/api';
import { rupees } from '@/lib/format';
import { useSnapshot } from '@/lib/use-snapshot';

type Dist = { n: number; p25_paise: number | null; median_paise: number | null; p75_paise: number | null;
  items: { pool_id: string; quote_id: string; variant_id: string; value_paise: number; ruleset_version: string; snapshot_sha256: string[] }[] };
type Pct = { n: number; p50: number | null; p95: number | null };
export type Analytics = {
  simulated: boolean;
  fill: { week: string; created: number; filled: number; median_hours_to_fill: number | null }[];
  dealer_response: { rounds: number; invited: number; responded: number };
  pools_with_two_valid_quotes: { quote_open: number; two_or_more: number };
  acceptance_to_confirmation: { orders: number; confirmed: number };
  confirmation_to_delivery: { closed_bookings: number; delivered: number };
  cancellation: { issued: number; cancelled: number; by_reason: Record<string, number> };
  void_rate: { accepted_quotes: number; void_quotes: number };
  refund_latency_hours: Pct;
  dispute_resolution_days: Pct;
  savings: Record<'dealer_stated_discount' | 'vs_catalogue_estimate' | 'vs_best_other_quote' | 'realised', Dist>;
};
const LABELS = ['dealer_stated_discount', 'vs_catalogue_estimate', 'vs_best_other_quote', 'realised'] as const;

/** E169 (P5.E): metrics.md figures, each with its sample size; every figure carries "simulated" while the data is synthetic. */
export function AnalyticsView() {
  const t = useTranslations('admin.analytics');
  const snap = useSnapshot(() => call(api.GET('/admin/analytics')), [], 60_000);
  const a = snap.data as Analytics | undefined; // E169 returns a free-form object (Map) in its OpenAPI document
  if (!a) return <Skeleton className="h-32" label={t('loading')} />;
  return <AnalyticsFigures a={a} />;
}

/** The figures alone: live (above) or the public demo's read-only admin view with synthetic data (P6.B). */
export function AnalyticsFigures({ a }: { a: Analytics }) {
  const t = useTranslations('admin.analytics');
  const sim = a.simulated ? <Badge tone="warning">{t('simulated')}</Badge> : null;
  const rate = (num: number, den: number) => (den === 0 ? '—' : `${((100 * num) / den).toFixed(1)}%`);
  const row = (label: string, num: number, den: number) => (
    <Card className="flex flex-wrap items-center gap-2">
      <CardTitle as="h3" className="text-base">{label}</CardTitle>
      <span className="tabular text-lg font-semibold">{rate(num, den)}</span>
      <span className="text-sm text-muted">{t('of', { num, den })}</span>
      {sim}
    </Card>
  );
  const money = (p: number | null) => (p == null ? '—' : rupees(p));
  return (
    <div className="flex flex-col gap-3">
      {a.simulated ? <p className="text-sm text-muted">{t('simulatedNote')}</p> : null}
      <Card className="flex flex-col gap-2">
        <CardTitle as="h3" className="text-base">{t('fill')} {sim}</CardTitle>
        {a.fill.length === 0 ? <p className="text-sm">—</p> : (
          <table className="text-sm"><thead><tr><th className="text-left">{t('week')}</th><th>{t('fillRate')}</th><th>{t('medianHours')}</th></tr></thead>
            <tbody>{a.fill.map((w) => (
              <tr key={w.week}><td>{w.week}</td><td className="tabular text-center">{rate(w.filled, w.created)} ({t('of', { num: w.filled, den: w.created })})</td>
                <td className="tabular text-center">{w.median_hours_to_fill ?? '—'}</td></tr>
            ))}</tbody></table>
        )}
      </Card>
      {row(t('dealerResponse'), a.dealer_response.responded, a.dealer_response.invited)}
      {row(t('twoValid'), a.pools_with_two_valid_quotes.two_or_more, a.pools_with_two_valid_quotes.quote_open)}
      {row(t('acceptToConfirm'), a.acceptance_to_confirmation.confirmed, a.acceptance_to_confirmation.orders)}
      {row(t('confirmToDeliver'), a.confirmation_to_delivery.delivered, a.confirmation_to_delivery.closed_bookings)}
      {row(t('cancellation'), a.cancellation.cancelled, a.cancellation.issued)}
      {row(t('voidRate'), a.void_rate.void_quotes, a.void_rate.accepted_quotes)}
      {[['refundLatency', a.refund_latency_hours], ['disputeTime', a.dispute_resolution_days]].map(([k, d]) => {
        const p = d as Pct;
        return (
          <Card key={k as string} className="flex flex-wrap items-center gap-2">
            <CardTitle as="h3" className="text-base">{t(k as string)}</CardTitle>
            <span className="tabular">{t('pct', { p50: p.p50 ?? '—', p95: p.p95 ?? '—' })}</span>
            <span className="text-sm text-muted">n = {p.n}</span>
            {sim}
          </Card>
        );
      })}
      <p className="text-sm text-muted">{Object.entries(a.cancellation.by_reason).map(([k, n]) => `${t(`reason.${k}`)} ${n}`).join(' · ')}</p>
      <h3 className="font-semibold">{t('savings')}</h3>
      <p className="text-sm text-muted">{t('savingsRule')}</p>
      {LABELS.map((k) => {
        const d = a.savings[k];
        return (
          <Card key={k} className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2"><CardTitle as="h3" className="text-base">{t(`label.${k}`)}</CardTitle>{sim}<span className="text-sm text-muted">n = {d.n}</span></div>
            <span className="tabular">{t('dist', { p25: money(d.p25_paise), median: money(d.median_paise), p75: money(d.p75_paise) })}</span>
            {d.items.length > 0 ? (
              <details className="text-xs"><summary>{t('explain')}</summary>
                <ul className="flex flex-col gap-1">{d.items.map((i) => (
                  <li key={`${i.quote_id}-${i.variant_id}`} className="break-all font-mono">{money(i.value_paise)} · {t('quote')} {i.quote_id.slice(-8)} · {i.ruleset_version} · {i.snapshot_sha256.map((s) => s.slice(0, 12)).join(', ')}</li>
                ))}</ul>
              </details>
            ) : null}
          </Card>
        );
      })}
    </div>
  );
}
