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
import { useOnline } from '@/lib/use-online';
import { type LiveStatus, useLiveSnapshot } from '@/lib/use-live-snapshot';
import { useQuoteCount } from '@/lib/use-quote-count';

type Membership = Schemas['pool']['Membership'];

declare global { interface Window { Razorpay?: new (o: Record<string, unknown>) => { open(): void; on(e: string, f: (x: unknown) => void): void } } }

const STATES = ['FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN', 'FINAL_ROUND', 'DECISION_OPEN', 'CONFIRMATION', 'BOOKING', 'EXPIRED', 'NO_QUOTES', 'CANCELLED'];

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

/** Live / reconnecting / delayed, announced politely when it changes. */
export function LiveIndicator({ status }: { status: LiveStatus }) {
  const t = useTranslations('pool.live');
  return (
    <span role="status" aria-live="polite" className="inline-flex items-center gap-1.5 text-sm text-muted">
      <span aria-hidden className={`size-2 rounded-full ${status === 'live' ? 'bg-success-fg' : status === 'reconnecting' ? 'bg-warning-fg' : 'bg-danger'}`} />
      {t(status)}
    </span>
  );
}

/** The membership the buyer sees while their join is in flight (replaced by the server's, or rolled back). */
function optimisticMembership(poolId: string): Membership {
  return { id: 'pending', pool_id: poolId, state: 'INTERESTED', current_payment_id: null, terms_version: null, accepted_quote_id: null, accepted_variant_id: null,
    release_reason: null, version: 0 };
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
  const tp = useTranslations('pool');
  const snap = useLiveSnapshot(['pool', poolId], () => call(api.GET('/pools/{id}', { params: { path: { id: poolId } } })), `/api/stream/pools/${poolId}`);
  const online = useOnline();
  const [ack, setAck] = React.useState(false);
  const pool = snap.data;
  const me = pool?.my_membership;
  // E122: live quote count while dealers quote (never contents); the closing signal reloads the snapshot (compare link).
  const quoting = !!pool && ['QUOTE_OPEN', 'FINAL_ROUND'].includes(pool.state) && !!me && me.state !== 'LEFT';
  const quoteCount = useQuoteCount(poolId, quoting, () => { snap.refresh(); });

  // Optimistic join: the count and "interested" show at once and roll back on failure. The version is not bumped, so the
  // stream's delta for this join (absolute counts, version + 1) applies on top without counting the buyer twice.
  const join = useCommand(async (key: string) => {
    const before = snap.data;
    snap.setData((p) => ({ ...p, interested_count: p.interested_count + 1, my_membership: optimisticMembership(p.id) }));
    try {
      const r = await call(api.POST('/pools/{id}/members', { params: { path: { id: poolId } }, headers: { 'Idempotency-Key': key } }));
      snap.setData((p) => ({ ...p, my_membership: r.data }));
      await snap.refresh();
      return r;
    } catch (e) {
      if (before) snap.setData(() => before);
      throw e;
    }
  });

  const commit = useCommand(async (key: string) => {
    const r = await call(api.POST('/pools/{id}/commitment', { params: { path: { id: poolId } }, body: { terms_version: pool!.terms_version }, headers: { 'Idempotency-Key': key } }));
    await snap.refresh();
    return r.data.deposit;
  });

  // Optimistic leave, rolled back on failure (e.g. a version conflict: the page then shows the latest state).
  const leave = useCommand(async (key: string) => {
    const before = snap.data;
    const wasCommitted = me && me.state !== 'INTERESTED';
    snap.setData((p) => ({ ...p, interested_count: Math.max(0, p.interested_count - 1), committed_count: Math.max(0, p.committed_count - (wasCommitted ? 1 : 0)),
      my_membership: p.my_membership ? { ...p.my_membership, state: 'LEFT' } : p.my_membership }));
    try {
      await call(api.DELETE('/pools/{id}/members/me', { params: { path: { id: poolId } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${me!.version}"` } }));
    } catch (e) {
      if (before) snap.setData(() => before);
      await snap.refresh();
      throw e;
    }
    await snap.refresh();
  });

  async function onCommit() {
    const dep = await commit.execute();
    if (!dep) return;
    try {
      await loadScript(checkoutUrl);
    } catch {
      commit.setState({ kind: 'error', problem: { status: 0, detail: tp('checkout.loadFailed') } });
      return;
    }
    const rzp = new window.Razorpay!({
      key: dep.razorpay_key_id, order_id: dep.razorpay_order_id, amount: dep.amount_paise, currency: 'INR', name: 'Convoy (TEST)', description: 'Test deposit',
      handler: async (resp: Record<string, string>) => {
        commit.setState({ kind: 'pending', message: tp('checkout.received') });
        await call(api.POST('/payments/{id}/checkout-callback', { params: { path: { id: dep.payment_id } }, body: resp as never }));
        const until = Date.now() + 90_000;
        while (Date.now() < until) {
          await new Promise((r) => setTimeout(r, 1500));
          const m = await call(api.GET('/pools/{id}/members/me', { params: { path: { id: poolId } } }));
          if (m.data.state === 'COMMITTED') { commit.setState({ kind: 'success', message: tp('checkout.committed') }); await snap.refresh(); return; }
        }
        commit.setState({ kind: 'pending', message: tp('checkout.stillConfirming') });
      },
    });
    rzp.on('payment.failed', () => commit.setState({ kind: 'error', problem: { status: 402, detail: tp('checkout.failed') } }));
    rzp.open();
  }

  if (snap.loading && !pool) return <Skeleton className="h-96" label={tp('checkout.loading')} />;
  if (!pool) {
    return (
      <div role="alert" className="flex flex-col items-start gap-2 rounded-lg bg-danger-bg p-3 text-danger">
        <p>{snap.problem?.detail ?? tp('loadFailed')}</p>
        <Button variant="secondary" onClick={() => snap.refresh()}>{tp('retry')}</Button>
      </div>
    );
  }
  // Stale: the last good snapshot is shown but may be behind (offline, or the latest refresh failed).
  const stale = !online || Boolean(snap.problem);

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
        <Badge tone="info">{STATES.includes(pool.state) ? tp(`state.${pool.state}`) : pool.state}</Badge>
        <LiveIndicator status={online ? snap.status : 'delayed'} />
      </div>
      {!online ? (
        <p role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">{tp('offline')}</p>
      ) : stale ? (
        <p role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">{tp('stale', { time: ist(new Date(snap.updatedAt).toISOString(), true) })}</p>
      ) : null}
      <div className="grid gap-4 md:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-3">
            <CardTitle>{tp('whoIsIn')}</CardTitle>
            <dl className="grid grid-cols-2 gap-3">
              <div><dt className="text-sm text-muted">{tp('committed')}</dt><dd className="tabular text-2xl font-bold">{pool.committed_count}</dd></div>
              <div><dt className="text-sm text-muted">{tp('interested')}</dt><dd className="tabular text-2xl font-bold">{pool.interested_count - pool.committed_count}</dd></div>
            </dl>
            {pool.interested_count === 0 && joinable ? <CardText>{tp('empty')}</CardText> : null}
            <div>
              <div className="h-3 overflow-hidden rounded-full border border-border bg-surface-2" role="progressbar" aria-valuemin={0} aria-valuemax={pool.min_members} aria-valuenow={pool.committed_count} aria-label={tp('progress')}>
                <div className="h-full bg-primary" style={{ width: `${progress}%` }} />
              </div>
              <CardText className="mt-1">{tp('minimum', { min: pool.min_members, month: pool.purchase_month })}</CardText>
            </div>
            {deadline ? <Countdown deadline={deadline} label={tp(pool.state === 'FORMING' ? 'deadline.forming' : pool.state === 'QUOTE_OPEN' ? 'deadline.sealed' : pool.state === 'DECISION_OPEN' ? 'deadline.decide' : 'deadline.confirm')} /> : null}
            {quoting && quoteCount !== null ? <p role="status" className="text-sm">{tp('quoteCount', { count: quoteCount })}</p> : null}
          </Card>
          {['EXPIRED', 'NO_QUOTES', 'CANCELLED'].includes(pool.state) || me?.release_reason === 'LATE_CAPTURE' ? <ClosedCard pool={pool} me={me ?? undefined} /> : null}
          {pool.state === 'DECISION_OPEN' && me && ['COMMITTED', 'ACCEPTED'].includes(me.state) ? (
            <Card emphasis><CardTitle>{tp('offersTitle')}</CardTitle><CardText className="mb-3">{tp('offersBody')}</CardText>
              <Button asChild><Link href={`/pools/${pool.id}/compare`}>{tp('compare')}</Link></Button></Card>
          ) : null}
          {['CONFIRMATION', 'BOOKING'].includes(pool.state) && me?.state === 'HANDED_TO_ORDER' ? (
            <Card emphasis><CardTitle>{tp('orderTitle')}</CardTitle><CardText className="mb-3">{tp('orderBody')}</CardText>
              <Button asChild><Link href="/orders">{tp('orders')}</Link></Button></Card>
          ) : null}
        </div>
        <Card className="flex flex-col gap-3">
          <CardTitle>{tp('yourPlace')}</CardTitle>
          <p className="text-sm">{me ? <>{tp('status')} <strong>{me.state.replace(/_/g, ' ').toLowerCase()}</strong></> : tp('notIn')}</p>
          {!me && joinable ? (
            <Button variant="secondary" disabled={!online} loading={join.state.kind === 'loading'} onClick={() => join.execute()}>{tp('join')}</Button>
          ) : null}
          <CommandStatus state={join.state} successText={tp('joined')} />
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
              <Button disabled={!ack || !online} loading={commit.state.kind === 'loading'} onClick={onCommit}>{t('commit')}</Button>
              <p className="text-xs text-muted">{tp('depositNote', { amount: rupees(pool.deposit_amount_paise) })}</p>
            </div>
          ) : null}
          <CommandStatus state={commit.state} />
          {me && me.id !== 'pending' && ['INTERESTED', 'COMMITTED', 'ACCEPTED'].includes(me.state) && ['FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN', 'FINAL_ROUND', 'DECISION_OPEN'].includes(pool.state) ? (
            <Button variant="link" disabled={!online} loading={leave.state.kind === 'loading'} onClick={() => leave.execute()}>{me.state !== 'INTERESTED' ? tp('leaveRefund') : tp('leave')}</Button>
          ) : null}
          <CommandStatus state={leave.state} successText={tp('left')} />
        </Card>
      </div>
      {/* eslint-disable-next-line react-hooks/purity -- footer timestamp fallback before the first load; display only */}
      <p className="text-xs text-muted">{tp(snap.status === 'live' && online ? 'footerLive' : 'footerPolling', { time: ist(new Date(snap.updatedAt || Date.now()).toISOString(), true) })}</p>
    </div>
  );
}
