'use client';
import Link from 'next/link';
import * as React from 'react';
import { Breakdown, type EngineOutput } from '@/components/breakdown';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Price } from '@/components/ui/price';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, etagVersion, type Schemas } from '@/lib/api';
import { rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

type Tier = Schemas['quote']['TierView'];
type OfferVariant = Schemas['quote']['OfferVariant'];
type Offer = Schemas['quote']['Offer'];

function useDealerName(id: string) {
  const [name, setName] = React.useState<string>('Dealer');
  React.useEffect(() => {
    call(api.GET('/dealerships/{id}', { params: { path: { id } } })).then((r) => setName(r.data.trade_name)).catch(() => {});
  }, [id]);
  return name;
}

function OfferCard({ offer, poolId, accepted, canAccept, onAccepted }: { offer: Offer; poolId: string; accepted: boolean; canAccept: boolean; onAccepted: () => void }) {
  const name = useDealerName(offer.dealership_id);
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
        {accepted ? <Badge tone="success">Your choice</Badge> : null}
        <Badge>{offer.state === 'FINAL' ? 'Final offer' : `Sealed offer v${offer.version_no}`}</Badge>
      </div>
      <div>
        <p className="text-sm font-semibold">Cash payable at your ceiling (the most you can pay)</p>
        <Price paise={v.ceiling.cash_payable_paise} size="lg" label="Cash payable at your ceiling" />
      </div>
      <p className="rounded-lg border border-dashed border-border px-3 py-2 text-sm">
        <span className="font-semibold">{v.projection.label}:</span>{' '}
        {v.projection.cash_payable_paise === null ? <span>{v.projection.note}</span> : <><span className="tabular">{rupees(v.projection.cash_payable_paise)}</span> <span className="text-muted">· projection, not guaranteed</span></>}
      </p>
      <div>
        <h4 className="text-sm font-semibold">Price can only fall</h4>
        <ol className="mt-1 flex flex-col gap-1 text-sm" aria-label="Tier ladder">
          {v.tiers.map((t) => (
            <li key={t.min} className={t.is_ceiling ? 'font-bold' : ''}>
              {t.max === null ? `${t.min}+` : `${t.min}–${t.max}`} confirmed: <span className="tabular">{rupees(t.cash_payable_paise)}</span>
              {t.is_ceiling ? ' — your ceiling' : ''}{t.max !== null && t.max < offer.min_fulfilment ? ' (offer void below the minimum)' : ''}
            </li>
          ))}
        </ol>
        <CardText className="mt-1">Minimum {offer.min_fulfilment} confirmed {offer.min_fulfilment === 1 ? 'buyer' : 'buyers'} · stock {v.stock.units} units{v.stock.colours?.length ? `, ${v.stock.colours.join('/')}` : ''}{v.stock.delivery_date ? ` · delivery from ${v.stock.delivery_date}` : ''}</CardText>
      </div>
      <details>
        <summary className="min-h-6 cursor-pointer text-sm font-semibold">Itemised breakdown at the ceiling</summary>
        <div className="mt-2"><Breakdown output={v.ceiling.output} caption={`${name}: breakdown at the ceiling tier`} /></div>
      </details>
      {canAccept ? (
        <>
          <Button variant={accepted ? 'secondary' : 'primary'} disabled={accepted} loading={accept.state.kind === 'loading'} onClick={() => accept.execute()}>
            {accepted ? 'Accepted' : 'Accept this offer (not binding)'}
          </Button>
          <CommandStatus state={accept.state} successText="Accepted. You can change your mind until the decision deadline." />
        </>
      ) : null}
    </Card>
  );
}

export function CompareView({ poolId }: { poolId: string }) {
  const pool = useSnapshot(() => call(api.GET('/pools/{id}', { params: { path: { id: poolId } } })), [poolId]);
  const offers = useSnapshot(() => call(api.GET('/pools/{poolId}/quotes', { params: { path: { poolId } } })), [poolId]);
  if ((pool.loading && !pool.data) || (offers.loading && !offers.data)) return <Skeleton className="h-96" label="Loading offers" />;
  if (!offers.data) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{offers.problem?.detail ?? 'Offers could not be loaded.'}</p>;
  const p = pool.data;
  const canAccept = p?.state === 'DECISION_OPEN' && ['COMMITTED', 'ACCEPTED'].includes(p.my_membership?.state ?? '');
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold">Compare offers</h1>
        <Link href={`/pools/${poolId}`} className="text-sm font-semibold text-primary underline">Back to the pool</Link>
      </div>
      {p?.decision_deadline && p.state === 'DECISION_OPEN' ? <Countdown deadline={p.decision_deadline} label="Decide by" /> : null}
      <p className="text-sm text-muted">Every figure comes from the same price engine. The ceiling is guaranteed; the projection is not. {offers.data.ranking_rule}</p>
      {offers.data.quotes.length === 0 ? <Card><p>No offers yet. Dealers have until the round deadline.</p></Card> : null}
      <div className="grid gap-4 lg:grid-cols-2">
        {offers.data.quotes.map((o) => (
          <OfferCard key={o.quote_id} offer={o} poolId={poolId} accepted={p?.my_membership?.accepted_quote_id === o.quote_id} canAccept={canAccept}
            onAccepted={() => { pool.refresh(); offers.refresh(); }} />
        ))}
      </div>
    </div>
  );
}
