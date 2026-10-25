'use client';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist, rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { type LiveStatus, useLiveSnapshot } from '@/lib/use-live-snapshot';

type Membership = Schemas['pool']['Membership'];
type Deposit = Schemas['pool']['Deposit'];

declare global { interface Window { Razorpay?: new (o: Record<string, unknown>) => { open(): void; on(e: string, f: (x: unknown) => void): void } } }

const STATE_TEXT: Record<string, string> = {
  FORMING: 'Gathering buyers', READY_FOR_QUOTES: 'Enough buyers: inviting dealers', QUOTE_OPEN: 'Dealers are quoting (sealed)',
  FINAL_ROUND: 'Best-and-final round', DECISION_OPEN: 'Offers are in: decide', CONFIRMATION: 'Confirm your price', BOOKING: 'Bookings issued',
  EXPIRED: 'Closed: not enough buyers', NO_QUOTES: 'Closed: no offers', CANCELLED: 'Closed',
};

/** Why the pool ended (or why the buyer's late payment was not used) and what happened to the deposit (scope A). */
function ClosedCard({ pool, me }: { pool: Schemas['pool']['Pool']; me: Membership | undefined }) {
  const t = useTranslations('closed');
  const [payment, setPayment] = React.useState<Schemas['payment']['PaymentView'] | null>(null);
  const paymentId = me?.current_payment_id;
  React.useEffect(() => {
    if (!paymentId) return;
    call(api.GET('/me/payments/{id}', { params: { path: { id: paymentId } } })).then((r) => setPayment(r.data)).catch(() => {});
  }, [paymentId, pool.version]);
  const reason = me?.release_reason === 'LATE_CAPTURE' ? 'LATE_CAPTURE' : pool.end_reason ?? '';
  const amount = rupees(pool.deposit_amount_paise);
  const money = !paymentId ? t('noDeposit') : !payment ? null
    : payment.state === 'REFUNDED' ? t('refunded', { amount }) : payment.state === 'VOIDED_UNPAID' ? t('voided') : t('refundPending', { amount });
  return (
    <Card emphasis className="flex flex-col gap-2">
      <CardTitle>{t('title')}</CardTitle>
      {reason && t.has(reason) ? <CardText>{t(reason, { min: pool.min_members, deadline: ist(pool.forming_deadline), brand: pool.brand, city: pool.city })}</CardText> : null}
      {money ? <p role="status" className="text-sm font-semibold">{money}</p> : null}
      <Button asChild variant="secondary" className="self-start"><Link href={`/models/${pool.model_slug}`}>{t('again')}</Link></Button>
    </Card>
  );
}

const LIVE_TEXT: Record<LiveStatus, string> = { live: 'Live', reconnecting: 'Reconnecting…', delayed: 'Updates delayed — refreshing every 10 s' };

/** Live / reconnecting / delayed, announced politely when it changes. */
export function LiveIndicator({ status }: { status: LiveStatus }) {
  return (
    <span role="status" aria-live="polite" className="inline-flex items-center gap-1.5 text-sm text-muted">
      <span aria-hidden className={`size-2 rounded-full ${status === 'live' ? 'bg-success-fg' : status === 'reconnecting' ? 'bg-warning-fg' : 'bg-danger'}`} />
      {LIVE_TEXT[status]}
    </span>
  );
}

function loadScript(src: string) {
  return new Promise<void>((resolve, reject) => {
    if (window.Razorpay) return resolve();
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('checkout script failed to load'));
    document.body.appendChild(s);
  });
}

export function PoolView({ poolId, checkoutUrl }: { poolId: string; checkoutUrl: string }) {
  const t = useTranslations('precommit');
  const snap = useLiveSnapshot(() => call(api.GET('/pools/{id}', { params: { path: { id: poolId } } })), `/api/stream/pools/${poolId}`, [poolId]);
  const [ack, setAck] = React.useState(false);
  const pool = snap.data;
  const me = pool?.my_membership;

  const join = useCommand(async (key: string) => {
    const r = await call(api.POST('/pools/{id}/members', { params: { path: { id: poolId } }, headers: { 'Idempotency-Key': key } }));
    await snap.refresh();
    return r;
  });

  const commit = useCommand(async (key: string) => {
    const r = await call(api.POST('/pools/{id}/commitment', { params: { path: { id: poolId } }, body: { terms_version: pool!.terms_version }, headers: { 'Idempotency-Key': key } }));
    await snap.refresh();
    return r.data.deposit;
  });

  const leave = useCommand(async (key: string) => {
    await call(api.DELETE('/pools/{id}/members/me', { params: { path: { id: poolId } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${me!.version}"` } }));
    await snap.refresh();
  });

  async function onCommit() {
    const dep = await commit.execute();
    if (!dep) return;
    try {
      await loadScript(checkoutUrl);
    } catch {
      commit.setState({ kind: 'error', problem: { status: 0, detail: 'The test checkout could not load. Your place is held; try again.' } });
      return;
    }
    const rzp = new window.Razorpay!({
      key: dep.razorpay_key_id, order_id: dep.razorpay_order_id, amount: dep.amount_paise, currency: 'INR', name: 'Convoy (TEST)', description: 'Test deposit',
      handler: async (resp: Record<string, string>) => {
        commit.setState({ kind: 'pending', message: 'Payment received by Razorpay — confirming (usually under a minute). TEST: no real money.' });
        await call(api.POST('/payments/{id}/checkout-callback', { params: { path: { id: dep.payment_id } }, body: resp as never }));
        const until = Date.now() + 90_000;
        while (Date.now() < until) {
          await new Promise((r) => setTimeout(r, 1500));
          const m = await call(api.GET('/pools/{id}/members/me', { params: { path: { id: poolId } } }));
          if (m.data.state === 'COMMITTED') { commit.setState({ kind: 'success', message: 'You are committed. Your ₹999 test deposit is held, not paid to anyone.' }); await snap.refresh(); return; }
        }
        commit.setState({ kind: 'pending', message: 'Still confirming with Razorpay. You can leave this page; we will show it here once confirmed.' });
      },
    });
    rzp.on('payment.failed', () => commit.setState({ kind: 'error', problem: { status: 402, detail: 'The test payment failed. Nothing was charged; you can try again.' } }));
    rzp.open();
  }

  if (snap.loading && !pool) return <Skeleton className="h-96" label="Loading the pool" />;
  if (!pool) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{snap.problem?.detail ?? 'This pool could not be loaded.'}</p>;

  const sealed = pool.rounds.find((r) => r.kind === 'SEALED');
  const joinable = ['FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN'].includes(pool.state);
  const canCommit = joinable && (!me || ['INTERESTED'].includes(me.state));
  const deadline = pool.state === 'FORMING' ? pool.forming_deadline : pool.state === 'QUOTE_OPEN' ? sealed?.deadline
    : pool.state === 'DECISION_OPEN' ? pool.decision_deadline : pool.state === 'CONFIRMATION' ? pool.confirmation_deadline : null;
  const progress = Math.min(100, Math.round((pool.committed_count / pool.min_members) * 100));

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-baseline gap-3">
        <h1 className="text-2xl font-extrabold">{pool.brand} {pool.model_slug.split('-').slice(1).join(' ')} pool, {pool.city}</h1>
        <Badge tone="info">{STATE_TEXT[pool.state] ?? pool.state}</Badge>
        <LiveIndicator status={snap.status} />
      </div>
      <div className="grid gap-4 md:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-3">
            <CardTitle>Who is in</CardTitle>
            <dl className="grid grid-cols-2 gap-3">
              <div><dt className="text-sm text-muted">Committed (deposit held)</dt><dd className="tabular text-2xl font-bold">{pool.committed_count}</dd></div>
              <div><dt className="text-sm text-muted">Interested, no deposit</dt><dd className="tabular text-2xl font-bold">{pool.interested_count - pool.committed_count}</dd></div>
            </dl>
            <div>
              <div className="h-3 overflow-hidden rounded-full border border-border bg-surface-2" role="progressbar" aria-valuemin={0} aria-valuemax={pool.min_members} aria-valuenow={pool.committed_count} aria-label="Committed buyers towards the minimum">
                <div className="h-full bg-primary" style={{ width: `${progress}%` }} />
              </div>
              <CardText className="mt-1">Minimum {pool.min_members} committed {pool.min_members === 1 ? 'buyer' : 'buyers'} before dealers are invited. Purchase month {pool.purchase_month}.</CardText>
            </div>
            {deadline ? <Countdown deadline={deadline} label={pool.state === 'FORMING' ? 'Forming closes' : pool.state === 'QUOTE_OPEN' ? 'Sealed round closes' : pool.state === 'DECISION_OPEN' ? 'Decide by' : 'Confirm by'} /> : null}
          </Card>
          {['EXPIRED', 'NO_QUOTES', 'CANCELLED'].includes(pool.state) || me?.release_reason === 'LATE_CAPTURE' ? <ClosedCard pool={pool} me={me ?? undefined} /> : null}
          {pool.state === 'DECISION_OPEN' && me && ['COMMITTED', 'ACCEPTED'].includes(me.state) ? (
            <Card emphasis><CardTitle>Offers are in</CardTitle><CardText className="mb-3">Compare the dealers&apos; itemised offers and accept one. Accepting is not binding.</CardText>
              <Button asChild><Link href={`/pools/${pool.id}/compare`}>Compare offers</Link></Button></Card>
          ) : null}
          {['CONFIRMATION', 'BOOKING'].includes(pool.state) && me?.state === 'HANDED_TO_ORDER' ? (
            <Card emphasis><CardTitle>Your order</CardTitle><CardText className="mb-3">Confirm your price, or see your booking.</CardText>
              <Button asChild><Link href="/orders">Go to my orders</Link></Button></Card>
          ) : null}
        </div>
        <Card className="flex flex-col gap-3">
          <CardTitle>Your place</CardTitle>
          <p className="text-sm">{me ? <>Status: <strong>{me.state.replace(/_/g, ' ').toLowerCase()}</strong></> : 'You are not in this pool yet.'}</p>
          {!me && joinable ? (
            <Button variant="secondary" loading={join.state.kind === 'loading'} onClick={() => join.execute()}>Join as interested (free)</Button>
          ) : null}
          <CommandStatus state={join.state} successText="You joined as interested." />
          {canCommit ? (
            <div className="flex flex-col gap-3 rounded-lg border border-border bg-surface-2 p-3">
              <h3 className="font-bold">{t('title')}</h3>
              <ul className="list-disc space-y-1 pl-5 text-sm">
                <li>{t('deposit')}</li>
                <li>{t('leave', { decisionDeadline: pool.decision_deadline ? ist(pool.decision_deadline) : t('decisionLater') })}</li>
                <li>{t('minimum', { min: pool.min_members, formingDeadline: ist(pool.forming_deadline) })}</li>
                <li>{t('noOffer')}</li>
                <li>{t('ceiling')}</li>
                <li>{t('cancel')}</li>
                <li>{t('dealer')}</li>
                <li>{t('refunds')}</li>
              </ul>
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-0.5 size-5 accent-[var(--primary)]" checked={ack} onChange={(e) => setAck(e.target.checked)} />
                <span>{t('ack', { version: pool.terms_version })}</span>
              </label>
              <Button disabled={!ack} loading={commit.state.kind === 'loading'} onClick={onCommit}>{t('commit')}</Button>
              <p className="text-xs text-muted">Deposit {rupees(pool.deposit_amount_paise)} · Razorpay test mode</p>
            </div>
          ) : null}
          <CommandStatus state={commit.state} />
          {me && ['INTERESTED', 'COMMITTED', 'ACCEPTED'].includes(me.state) && ['FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN', 'FINAL_ROUND', 'DECISION_OPEN'].includes(pool.state) ? (
            <Button variant="link" loading={leave.state.kind === 'loading'} onClick={() => leave.execute()}>Leave this pool{me.state !== 'INTERESTED' ? ' (full ₹999 refund)' : ''}</Button>
          ) : null}
          <CommandStatus state={leave.state} successText="You left the pool. Any deposit is being refunded in full." />
        </Card>
      </div>
      <p className="text-xs text-muted">This page refreshes every 10 seconds. Last update {ist(pool.server_time, true)}.</p>
    </div>
  );
}
