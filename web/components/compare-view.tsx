'use client';
import Link from 'next/link';
import * as React from 'react';
import { Breakdown, Flags } from '@/components/breakdown';
import { BuyerClarifications } from '@/components/clarifications';
import { CommandStatus } from '@/components/command-status';
import { Badge, EvidenceBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Price } from '@/components/ui/price';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, etagVersion, type Schemas } from '@/lib/api';
import { cn } from '@/lib/cn';
import { keyLines, matrixRows, shownVariant, sortOffers } from '@/lib/compare';
import { ist, lineLabel, rupees, type Evidence } from '@/lib/format';
import { useModel } from '@/lib/use-model';
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
      <h3 className="text-sm font-semibold md:sr-only">{t('priceOnlyFalls')}</h3>
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
    // One row per add-on: an offer pricing several variants repeats the same line on each.
    const lonely = [...new Map(requiredAddons(o).filter((l) => !others.has(l.code)).map((l) => [l.code, l])).values()];
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

const ROW = 'border-b border-border py-2 md:px-3';

/** One dealer's column of the matrix on a wide screen, one card on a phone: the same DOM, with rows aligned across columns by subgrid. */
export function OfferCard({ offer, poolId, acceptedVariantId, canAccept, committed, flagged, onAccepted, chosenId, onChoose, rows, delta }: {
  offer: Offer; poolId: string; acceptedVariantId: string | null; canAccept: boolean; committed: number; flagged: Line[]; onAccepted: () => void;
  chosenId?: string | null; onChoose?: (variantId: string) => void;
  /** Matrix rows shared by all offers; alone, a card has only its own. */ rows?: { key: string; differs: boolean }[];
  /** Above the lowest ceiling: 0 for the lowest; undefined when there is nothing to compare. */ delta?: { paise: number; of: number };
}) {
  const t = useTranslations('compare');
  const { name, reliability } = useDealer(offer.dealership_id);
  const v = shownVariant(offer, chosenId, acceptedVariantId);
  const accepted = acceptedVariantId !== null && acceptedVariantId === v.variant_id;
  const acceptedOther = acceptedVariantId !== null && !accepted;
  const accept = useCommand(async (key: string) => {
    const me = await call(api.GET('/pools/{id}/members/me', { params: { path: { id: poolId } } }));
    await call(api.PUT('/pools/{id}/members/me/acceptance', {
      params: { path: { id: poolId } }, body: { quote_id: offer.quote_id, variant_id: v.variant_id },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${etagVersion(me.response)}"` },
    }));
    onAccepted();
  });
  const lines = keyLines(v.ceiling.output.lines);
  const atZero = v.projection.count === 0;
  const colours = v.stock.colours?.length ? v.stock.colours.join('/') : null;
  return (
    <article className={cn('flex min-w-0 flex-col rounded-lg border bg-surface p-4 md:grid md:grid-rows-subgrid md:p-0 md:[grid-row:1/-1]', accepted ? 'border-2 border-primary' : 'border-border')}>
      <div className={cn(ROW, 'flex flex-col gap-2 pt-0 md:pt-3')}>
        <h2 className="text-lg font-semibold leading-snug">{name}</h2>
        <div className="flex flex-wrap gap-2">
          {accepted ? <Badge tone="success">{t('yourChoice')}</Badge> : null}
          <Badge>{offer.state === 'FINAL' ? t('finalOffer') : t('sealedOffer', { version: offer.version_no })}</Badge>
        </div>
        <ReliabilityNote r={reliability} />
        {offer.variants.length > 1 ? (
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-semibold">{t('variantChoose')}</legend>
            {offer.variants.map((x) => (
              <label key={x.variant_id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-control px-3 has-[:checked]:border-primary has-[:checked]:bg-surface-2">
                <input type="radio" name={`variant-${offer.quote_id}`} checked={x.variant_id === v.variant_id} onChange={() => onChoose?.(x.variant_id)} className="size-4 accent-[var(--primary)]" />
                <span className="flex-1">{x.variant_name}</span>
                <span className="tabular font-semibold">{rupees(x.ceiling.cash_payable_paise)}</span>
              </label>
            ))}
          </fieldset>
        ) : <p className="font-semibold">{t('variantOne', { variant: v.variant_name })}</p>}
        {acceptedOther ? <p className="text-sm text-muted">{t('acceptedOther')}</p> : null}
        {flagged.length ? (
          <p role="note" className="rounded-lg bg-warning-bg p-2 text-sm text-warning-fg">
            {t('addonFlag', { items: flagged.map((l) => `${lineLabel(l.code)} ${rupees(l.amount_paise)}`).join(', ') })}
          </p>
        ) : null}
        <p className="text-xs text-muted">{t('lastUpdated', { time: ist(offer.committed_at), version: offer.version_no })}</p>
      </div>
      <div className={ROW}>
        <p className="text-sm font-semibold md:sr-only">{t('ceilingTitle')}</p>
        <Price paise={v.ceiling.cash_payable_paise} size="lg" label={t('ceilingLabel')} />
        {delta ? <p className="text-sm font-semibold">{delta.paise === 0 ? t('lowest', { count: delta.of }) : t('moreThanLowest', { amount: rupees(delta.paise) })}</p> : null}
      </div>
      {(rows ?? lines.map((l) => ({ key: l.key, differs: false }))).map(({ key, differs }) => {
        const line = lines.find((l) => l.key === key);
        return (
          <div key={key} className={cn(ROW, 'flex items-start justify-between gap-3 text-sm md:block md:text-right', differs && 'md:bg-surface-2', !line && 'max-md:hidden')}>
            <span className="md:sr-only">{rowLabel(t, key)}</span>
            <span className="flex flex-col items-end text-right">
              {line ? <>
                <span className="tabular">{rupees(line.amount_paise)}</span>
                <EvidenceBadge evidence={line.evidence} />
                <Flags l={line} />
              </> : <span className="text-muted">{t('rowNone')}</span>}
            </span>
          </div>
        );
      })}
      <div className={cn(ROW, 'text-sm')}>
        {atZero ? <p>{t('minimumNeeded', { min: offer.min_fulfilment })}</p> : (
          <>
            <p><span className="font-semibold">{v.projection.label}:</span>{' '}
              {v.projection.cash_payable_paise === null ? <span>{v.projection.note}</span> : <><span className="tabular">{rupees(v.projection.cash_payable_paise)}</span> <span className="text-muted">{t('notGuaranteed')}</span></>}</p>
            <p className="text-muted">{t('minimumNeeded', { min: offer.min_fulfilment })}</p>
          </>
        )}
      </div>
      <div className={ROW}><TierLadder tiers={v.tiers} minFulfilment={offer.min_fulfilment} /></div>
      <p className={cn(ROW, 'text-sm')}><span className="font-semibold md:sr-only">{t('stock')}</span> <StockNote stock={v.stock} committed={committed} />{colours ? ` · ${colours}` : ''}</p>
      <div className={cn(ROW, 'border-b-0 md:border-b')}>
        {canAccept ? (
          <div className="flex flex-col gap-2">
            <Button block variant={accepted ? 'secondary' : 'primary'} disabled={accepted} loading={accept.state.kind === 'loading'} onClick={() => accept.execute()}>
              {accepted ? t('accepted') : t('accept')}
            </Button>
            <CommandStatus state={accept.state} successText={t('acceptedNote')} />
          </div>
        ) : null}
      </div>
      <div className="flex flex-col gap-2 py-2 md:px-3 md:pb-3">
        <details>
          <summary className="min-h-6 cursor-pointer text-sm font-semibold">{t('breakdownSummary')}</summary>
          <div className="mt-2"><Breakdown stacked output={v.ceiling.output} caption={`${name}, ${v.variant_name}: breakdown at the ceiling tier`} /></div>
        </details>
        {offer.state === 'FINAL' ? <BuyerClarifications poolId={poolId} quoteId={offer.quote_id} items={offer.clarifications} onChange={onAccepted} /> : null}
      </div>
    </article>
  );
}

const rowLabel = (t: ReturnType<typeof useTranslations<'compare'>>, key: string) => (t.has(`row.${key}` as never) ? t(`row.${key}` as never) : lineLabel(key));
const EVIDENCE: Evidence[] = ['DEALER_CONFIRMED', 'CATALOGUE_ESTIMATE', 'UNVERIFIED'];

export function CompareView({ poolId }: { poolId: string }) {
  const t = useTranslations('compare');
  const pool = useSnapshot(() => call(api.GET('/pools/{id}', { params: { path: { id: poolId } } })), [poolId]);
  const offers = useSnapshot(() => call(api.GET('/pools/{poolId}/quotes', { params: { path: { poolId } } })), [poolId]);
  const model = useModel(pool.data?.model_slug);
  const [chosen, setChosen] = React.useState<Record<string, string>>({});
  if ((pool.loading && !pool.data) || (offers.loading && !offers.data)) return <Skeleton className="h-96" label={t('loading')} />;
  if (!offers.data) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{offers.problem?.detail ?? t('loadFailed')}</p>;
  const p = pool.data;
  const canAccept = p?.state === 'DECISION_OPEN' && ['COMMITTED', 'ACCEPTED'].includes(p.my_membership?.state ?? '');
  const acceptedOf = (o: Offer) => (p?.my_membership?.accepted_quote_id === o.quote_id ? p.my_membership.accepted_variant_id ?? o.variants[0].variant_id : null);
  const shown = (o: Offer) => shownVariant(o, chosen[o.quote_id], acceptedOf(o));
  const sorted = sortOffers(offers.data.quotes, shown);
  const lowest = sorted.length ? shown(sorted[0]).ceiling.cash_payable_paise : 0;
  const rows = matrixRows(sorted.map((o) => keyLines(shown(o).ceiling.output.lines)));
  const mixed = new Set(sorted.map((o) => shown(o).variant_id)).size > 1;
  const flags = addonFlags(sorted);
  const labels = [null, t('ceilingTitle'), ...rows.map((r) => rowLabel(t, r.key)), t('rowMinimum'), t('priceOnlyFalls'), t('stock'), null, null];
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('title')}</h1>
          {p ? <p className="font-semibold">{t('subtitle', { car: model ? `${model.brand} ${model.name}` : p.brand, city: p.city, count: sorted.length })}</p> : null}
        </div>
        <Link href={`/pools/${poolId}`} className="text-sm font-semibold text-primary underline">{t('back')}</Link>
      </div>
      {p?.decision_deadline && p.state === 'DECISION_OPEN' ? <Countdown deadline={p.decision_deadline} label={t('decideBy')} /> : null}
      <p className="text-sm text-muted">{t('sorted')}</p>
      {sorted.length ? (
        <ul className="flex flex-col gap-1 text-xs text-muted" aria-label={t('legendTitle')}>
          {EVIDENCE.map((e) => <li key={e} className="flex flex-wrap gap-x-1"><span><EvidenceBadge evidence={e} />:</span> {t(`legend.${e}`)}</li>)}
        </ul>
      ) : null}
      {mixed ? <p role="note" className="rounded-lg bg-warning-bg p-2 text-sm text-warning-fg">{t('mixedVariants')}</p> : null}
      {sorted.length === 0 ? <Card><p>{t('none')}</p></Card> : (
        <div tabIndex={0} role="region" aria-label={t('title')} style={{ '--n': sorted.length, '--r': labels.length } as React.CSSProperties}
          className="flex flex-col gap-4 md:grid md:grid-cols-[minmax(8rem,10rem)_repeat(var(--n),minmax(12rem,1fr))] md:grid-rows-[repeat(var(--r),auto)] md:gap-x-2 md:gap-y-0 md:overflow-x-auto">
          <div aria-hidden className="hidden text-sm font-semibold md:grid md:grid-rows-subgrid md:[grid-row:1/-1]">
            {labels.map((l, i) => {
              const row = rows[i - 2];
              return <div key={i} className={cn(l ? ROW : 'py-2', 'md:px-2', row?.differs && 'md:bg-surface-2')}>{l}</div>;
            })}
          </div>
          {sorted.map((o) => (
            <OfferCard key={o.quote_id} offer={o} poolId={poolId} acceptedVariantId={acceptedOf(o)} canAccept={canAccept} committed={p?.committed_count ?? 0} flagged={flags[o.quote_id] ?? []}
              chosenId={chosen[o.quote_id]} onChoose={(id) => setChosen((c) => ({ ...c, [o.quote_id]: id }))} rows={rows}
              delta={sorted.length > 1 ? { paise: shown(o).ceiling.cash_payable_paise - lowest, of: sorted.length } : undefined}
              onAccepted={() => { pool.refresh(); offers.refresh(); }} />
          ))}
        </div>
      )}
    </div>
  );
}
