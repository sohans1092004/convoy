'use client';
import Link from 'next/link';
import * as React from 'react';
import { Breakdown } from '@/components/breakdown';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Price } from '@/components/ui/price';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, etagVersion, type Schemas } from '@/lib/api';
import { ist, lineLabel, rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';
import { useTranslations } from 'next-intl';

type Offer = Schemas['quote']['Offer'];
type TierView = Schemas['quote']['TierView'];
type Stock = Schemas['quote']['Stock'];

/** The ladder: each confirmed-buyer range with its price; the ceiling is marked; the price can only fall. */
export function TierLadder({ tiers, minFulfilment }: { tiers: TierView[]; minFulfilment: number }) {
  const t = useTranslations('compare');
  return (
    <div>
      <h4 className="text-sm font-semibold">{t('priceOnlyFalls')}</h4>
      <ol className="mt-1 flex flex-col gap-1 text-sm" aria-label={t('tierLadder')}>
        {tiers.map((r) => (
          <li key={r.min} className={r.is_ceiling ? 'font-bold' : ''}>
            {r.max === null ? `${r.min}+` : `${r.min}–${r.max}`} {t('confirmed')}: <span className="tabular">{rupees(r.cash_payable_paise)}</span>
            {r.is_ceiling ? ` ${t('yourCeiling')}` : ''}{r.max !== null && r.max < minFulfilment ? ` ${t('voidBelowMin')}` : ''}
          </li>
        ))}
      </ol>
    </div>
  );
}

/** How sure the stock claim is: the dealer's own statement, compared with how many buyers have committed so far. */
export function stockConfidence(stock: Stock, committed: number): 'enough' | 'short' | 'none' {
  return stock.units >= Math.max(1, committed) ? 'enough' : stock.units > 0 ? 'short' : 'none';
}

export function StockNote({ stock, committed }: { stock: Stock; committed: number }) {
  const t = useTranslations('compare.stockNote');
  return (
    <>
      {t(stockConfidence(stock, committed), { units: stock.units, committed })}{' '}
      {stock.delivery_date ? t('statementWithDate', { date: stock.delivery_date }) : t('statement')}
    </>
  );
}

type Line = Offer['variants'][number]['ceiling']['output']['lines'][number];
/** Dealer-required add-ons (accessories the buyer cannot decline) in an offer's ceiling price. */
export const requiredAddons = (offer: Offer): Line[] =>
  offer.variants.flatMap((v) => v.ceiling.output.lines.filter((l) => l.code.startsWith('ACCESSORY:') && l.flags.includes('DEALER_REQUIRED')));

/** Flags each dealer-required add-on that no other offer in the comparison requires (P3.F: the mispriced add-on case). */
export function addonFlags(offers: Offer[]): Record<string, Line[]> {
  const out: Record<string, Line[]> = {};
  for (const o of offers) {
    const others = new Set(offers.filter((x) => x.quote_id !== o.quote_id).flatMap((x) => requiredAddons(x).map((l) => l.code)));
    const lonely = requiredAddons(o).filter((l) => !others.has(l.code));
    if (lonely.length) out[o.quote_id] = lonely;
  }
  return out;
}

type Reliability = Schemas['dealer']['Reliability'];

function useDealer(id: string) {
  const [d, setD] = React.useState<{ name: string; reliability: Reliability | null }>({ name: 'Dealer', reliability: null });
  React.useEffect(() => {
    call(api.GET('/dealerships/{id}', { params: { path: { id } } })).then((r) => setD({ name: r.data.trade_name, reliability: r.data.reliability })).catch(() => {});
  }, [id]);
  return d;
}

/** AMENDMENT-009: the reliability score always comes with its counts and window, never as a bare percentage. */
export function ReliabilityNote({ r }: { r: Reliability | null }) {
  const t = useTranslations('compare');
  if (!r) return null;
  return <p className="text-sm text-muted">{r.total === 0 ? t('reliabilityNone', { days: r.window_days })
    : t('reliability', { honoured: r.honoured, total: r.total, days: r.window_days, percent: r.score_percent ?? 0 })}</p>;
}

function OfferCard({ offer, poolId, accepted, canAccept, committed, flagged, onAccepted }: { offer: Offer; poolId: string; accepted: boolean; canAccept: boolean; committed: number; flagged: Line[]; onAccepted: () => void }) {
  const t = useTranslations('compare');
  const { name, reliability } = useDealer(offer.dealership_id);
  const v = offer.variants[0];
  const accept = useCommand(async (key: string) => {
    const me = await call(api.GET('/pools/{id}/members/me', { params: { path: { id: poolId } } }));
    await call(api.PUT('/pools/{id}/members/me/acceptance', {
      params: { path: { id: poolId } }, body: { quote_id: offer.quote_id, variant_id: v.variant_id },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${etagVersion(me.response)}"` },
    }));
    onAccepted();
  });
  return (
    <Card emphasis={accepted} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <CardTitle as="h3">{name}</CardTitle>
        {accepted ? <Badge tone="success">{t('yourChoice')}</Badge> : null}
        <Badge>{offer.state === 'FINAL' ? t('finalOffer') : t('sealedOffer', { version: offer.version_no })}</Badge>
        <span className="basis-full"><ReliabilityNote r={reliability} /></span>
      </div>
      {flagged.length ? (
        <p role="note" className="rounded-lg bg-warning-bg p-2 text-sm text-warning-fg">
          {t('addonFlag', { items: flagged.map((l) => `${lineLabel(l.code)} ${rupees(l.amount_paise)}`).join(', ') })}
        </p>
      ) : null}
      <p className="text-xs text-muted">{t('lastUpdated', { time: ist(offer.committed_at), version: offer.version_no })}</p>
      <div>
        <p className="text-sm font-semibold">{t('ceilingTitle')}</p>
        <Price paise={v.ceiling.cash_payable_paise} size="lg" label={t('ceilingLabel')} />
      </div>
      <p className="rounded-lg border border-dashed border-border px-3 py-2 text-sm">
        <span className="font-semibold">{v.projection.label}:</span>{' '}
        {v.projection.cash_payable_paise === null ? <span>{v.projection.note}</span> : <><span className="tabular">{rupees(v.projection.cash_payable_paise)}</span> <span className="text-muted">{t('notGuaranteed')}</span></>}
      </p>
      <TierLadder tiers={v.tiers} minFulfilment={offer.min_fulfilment} />
      <CardText>Minimum {offer.min_fulfilment} confirmed {offer.min_fulfilment === 1 ? 'buyer' : 'buyers'}{v.stock.colours?.length ? ` · ${v.stock.colours.join('/')}` : ''}</CardText>
      <p className="text-sm"><span className="font-semibold">{t('stock')}</span> <StockNote stock={v.stock} committed={committed} /></p>
      <details>
        <summary className="min-h-6 cursor-pointer text-sm font-semibold">{t('breakdownSummary')}</summary>
        <div className="mt-2"><Breakdown output={v.ceiling.output} caption={`${name}: breakdown at the ceiling tier`} /></div>
      </details>
      {canAccept ? (
        <>
          <Button variant={accepted ? 'secondary' : 'primary'} disabled={accepted} loading={accept.state.kind === 'loading'} onClick={() => accept.execute()}>
            {accepted ? t('accepted') : t('accept')}
          </Button>
          <CommandStatus state={accept.state} successText={t('acceptedNote')} />
        </>
      ) : null}
    </Card>
  );
}

export function CompareView({ poolId }: { poolId: string }) {
  const t = useTranslations('compare');
  const pool = useSnapshot(() => call(api.GET('/pools/{id}', { params: { path: { id: poolId } } })), [poolId]);
  const offers = useSnapshot(() => call(api.GET('/pools/{poolId}/quotes', { params: { path: { poolId } } })), [poolId]);
  if ((pool.loading && !pool.data) || (offers.loading && !offers.data)) return <Skeleton className="h-96" label={t('loading')} />;
  if (!offers.data) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{offers.problem?.detail ?? 'Offers could not be loaded.'}</p>;
  const p = pool.data;
  const canAccept = p?.state === 'DECISION_OPEN' && ['COMMITTED', 'ACCEPTED'].includes(p.my_membership?.state ?? '');
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold">{t('title')}</h1>
        <Link href={`/pools/${poolId}`} className="text-sm font-semibold text-primary underline">{t('back')}</Link>
      </div>
      {p?.decision_deadline && p.state === 'DECISION_OPEN' ? <Countdown deadline={p.decision_deadline} label={t('decideBy')} /> : null}
      <p className="text-sm text-muted">Every figure comes from the same price engine. The ceiling is guaranteed; the projection is not. {offers.data.ranking_rule}</p>
      {offers.data.quotes.length === 0 ? <Card><p>{t('none')}</p></Card> : null}
      <div className="grid gap-4 lg:grid-cols-2">
        {(() => { const flags = addonFlags(offers.data.quotes); return offers.data.quotes.map((o) => (
          <OfferCard key={o.quote_id} offer={o} poolId={poolId} accepted={p?.my_membership?.accepted_quote_id === o.quote_id} canAccept={canAccept} committed={p?.committed_count ?? 0} flagged={flags[o.quote_id] ?? []}
            onAccepted={() => { pool.refresh(); offers.refresh(); }} />
        )); })()}
      </div>
    </div>
  );
}
