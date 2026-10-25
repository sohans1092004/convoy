'use client';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import QRCode from 'qrcode';
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
import { ist, rupees } from '@/lib/format';
import { timeline, type DepositState, type StepStatus } from '@/lib/order-timeline';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

type Order = Schemas['order']['Order'];
type Voucher = Schemas['order']['Voucher'];
const DISPUTE_CATEGORIES = ['NOT_DELIVERED', 'DELIVERY_OVERDUE', 'PRICE_NOT_HONOURED', 'WRONG_VARIANT', 'CHARGES_DISPUTE', 'REFUND_NOT_RECEIVED', 'OTHER'] as const;

function VoucherCard({ orderId }: { orderId: string }) {
  const to = useTranslations('order');
  const [v, setV] = React.useState<Voucher | null>(null);
  const [svg, setSvg] = React.useState('');
  React.useEffect(() => {
    call(api.GET('/orders/{id}/voucher', { params: { path: { id: orderId } } })).then(async (r) => {
      setV(r.data);
      setSvg(await QRCode.toString(r.data.qr_payload, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }));
    }).catch(() => {});
  }, [orderId]);
  if (!v) return <Skeleton className="h-64" label={to('loadingVoucher')} />;
  return (
    <Card emphasis className="flex flex-col items-start gap-3 sm:flex-row">
      <div role="img" aria-label={`QR code for booking voucher ${v.id.slice(-8)}`} className="w-48 shrink-0 rounded-lg bg-white p-2" dangerouslySetInnerHTML={{ __html: svg }} />
      <div className="flex flex-col gap-1">
        <CardTitle>{to('voucher')}</CardTitle>
        <p className="text-sm">{to('booking')} <span className="font-mono">{v.id.slice(-8).toUpperCase()}</span> · <Badge tone="success">{v.state.replace(/_/g, ' ').toLowerCase()}</Badge></p>
        {v.promised_delivery_date ? <p className="text-sm">{to('promised', { date: v.promised_delivery_date })}</p> : null}
        <CardText>{to('qrNote')}</CardText>
      </div>
    </Card>
  );
}

const DOT: Record<StepStatus, string> = { done: 'bg-success-fg border-success-fg', current: 'bg-primary border-primary', upcoming: 'bg-surface border-border', ended: 'bg-surface-2 border-border' };

/** The order's whole life on one line of steps; the current one is announced to screen readers. */
function Timeline({ order, deposit }: { order: Order; deposit: DepositState | null }) {
  const t = useTranslations('timeline');
  const { steps, next } = timeline(order, deposit);
  return (
    <Card className="flex flex-col gap-3">
      <CardTitle>{t('title')}</CardTitle>
      <ol className="flex flex-col gap-2" aria-label={t('title')}>
        {steps.map((s) => (
          <li key={s.key} className="flex items-start gap-3" aria-current={s.status === 'current' ? 'step' : undefined}>
            <span aria-hidden className={`mt-1 h-3 w-3 shrink-0 rounded-full border-2 ${DOT[s.status]}`} />
            <span className={s.status === 'ended' ? 'text-muted line-through' : s.status === 'upcoming' ? 'text-muted' : ''}>
              {t(`step.${s.key}`)}
              {s.key === 'deposit' && deposit ? <span className="text-sm text-muted"> · {t(`deposit.${deposit}`)}</span> : null}
              <span className="sr-only"> ({t(`status.${s.status}`)})</span>
            </span>
          </li>
        ))}
      </ol>
      <p className="rounded-lg bg-surface-2 p-3 text-sm font-semibold" data-testid="next-action">{t(`next.${next}`)}</p>
    </Card>
  );
}

function DisputeForm({ order }: { order: Order }) {
  const t = useTranslations('lifecycle');
  const router = useRouter();
  const [category, setCategory] = React.useState<string>(order.booking?.overdue_flag ? 'DELIVERY_OVERDUE' : 'NOT_DELIVERED');
  const [text, setText] = React.useState('');
  const open = useCommand(async (key: string) => {
    const r = await call(api.POST('/orders/{id}/disputes', { params: { path: { id: order.id } }, body: { category, description: text }, headers: { 'Idempotency-Key': key } }));
    router.push(`/disputes/${r.data.id}`);
  });
  return (
    <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); open.execute(); }}>
      <label className="flex flex-col gap-1 text-sm">{t('disputeCategory')}
        <select className="rounded-lg border border-border bg-surface p-2" value={category} onChange={(e) => setCategory(e.target.value)}>
          {DISPUTE_CATEGORIES.map((c) => <option key={c} value={c}>{t(`category.${c}`)}</option>)}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-sm">{t('disputeWhat')}
        <textarea required maxLength={4000} rows={3} className="rounded-lg border border-border bg-surface p-2" value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <Button type="submit" variant="secondary" className="self-start" loading={open.state.kind === 'loading'}>{t('openDispute')}</Button>
      <CommandStatus state={open.state} />
    </form>
  );
}

function ReviewForm({ bookingId }: { bookingId: string }) {
  const t = useTranslations('lifecycle');
  const [rating, setRating] = React.useState(5);
  const [body, setBody] = React.useState('');
  const send = useCommand(async (key: string) =>
    call(api.POST('/bookings/{bookingId}/review', { params: { path: { bookingId } }, body: { rating, body }, headers: { 'Idempotency-Key': key } })));
  return (
    <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); send.execute(); }}>
      <fieldset className="flex flex-wrap gap-3"><legend className="mb-1 text-sm">{t('reviewRating')}</legend>
        {[1, 2, 3, 4, 5].map((n) => (
          <label key={n} className="flex items-center gap-1 text-sm"><input type="radio" name="rating" value={n} checked={rating === n} onChange={() => setRating(n)} />{n}</label>
        ))}
      </fieldset>
      <label className="flex flex-col gap-1 text-sm">{t('reviewText')}
        <textarea maxLength={2000} rows={3} className="rounded-lg border border-border bg-surface p-2" value={body} onChange={(e) => setBody(e.target.value)} />
      </label>
      <Button type="submit" className="self-start" loading={send.state.kind === 'loading'} disabled={send.state.kind === 'success'}>{t('postReview')}</Button>
      <CommandStatus state={send.state} successText={t('reviewPosted')} />
    </form>
  );
}

export function OrderView({ orderId }: { orderId: string }) {
  const to = useTranslations('order');
  const t = useTranslations('confirm');
  const tl = useTranslations('lifecycle');
  const snap = useSnapshot(async () => {
    const o = await call(api.GET('/orders/{id}', { params: { path: { id: orderId } } }));
    // The deposit's state lives in payment: the pool membership names the payment.
    let deposit: DepositState | null = null;
    try {
      const m = await call(api.GET('/pools/{id}/members/me', { params: { path: { id: o.data.pool_id } } }));
      if (m.data.current_payment_id) deposit = (await call(api.GET('/me/payments/{id}', { params: { path: { id: m.data.current_payment_id } } }))).data.state;
    } catch { /* the timeline shows the deposit step without its detail */ }
    return { data: { order: o.data, deposit, etag: etagVersion(o.response) }, response: o.response };
  }, [orderId], 4_000);
  const fresh = async () => (await snap.refresh());
  const confirm = useCommand(async (key: string) => {
    const r = await call(api.GET('/orders/{id}', { params: { path: { id: orderId } } }));
    await call(api.POST('/orders/{id}/confirm', {
      params: { path: { id: orderId } }, body: { ceiling_snapshot_hash: r.data.snapshots.ceiling!.sha256 },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${etagVersion(r.response)}"` },
    }));
    await fresh();
  });
  const decline = useCommand(async (key: string) => {
    await call(api.POST('/orders/{id}/decline', { params: { path: { id: orderId } }, headers: { 'Idempotency-Key': key } }));
    await fresh();
  });
  const cancel = useCommand(async (key: string) => {
    const o = snap.data!.order;
    if (o.booking) {
      await call(api.POST('/bookings/{id}/cancel', { params: { path: { id: o.booking.id } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${o.booking.version}"` } }));
    } else {
      await call(api.POST('/orders/{id}/cancel', { params: { path: { id: orderId } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${snap.data!.etag}"` } }));
    }
    await fresh();
  });
  const delivered = useCommand(async (key: string) => {
    await call(api.POST('/bookings/{id}/confirm-delivery', { params: { path: { id: snap.data!.order.booking!.id } }, headers: { 'Idempotency-Key': key } }));
    await fresh();
  });
  const [confirmCancel, setConfirmCancel] = React.useState(false);
  const o = snap.data?.order;
  if (snap.loading && !o) return <Skeleton className="h-96" label={to('loading')} />;
  if (!o) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{snap.problem?.detail ?? to('loadFailed')}</p>;
  const ceiling = o.snapshots.ceiling!;
  const b = o.booking;
  const dealerFailure = o.disputes.length > 0;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">{to('title')}</h1>
        <Badge tone={['BOOKED', 'COMPLETED'].includes(o.state) ? 'success' : o.state === 'CONFIRMATION_PENDING' ? 'warning' : 'neutral'}>{o.state.replace(/_/g, ' ').toLowerCase()}</Badge>
      </div>
      <Timeline order={o} deposit={snap.data!.deposit} />
      {o.state === 'CONFIRMATION_PENDING' ? (
        <Card emphasis className="flex flex-col gap-3">
          <CardTitle>{t('title', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</CardTitle>
          <p className="text-sm">{t('body', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</p>
          <p className="text-sm">{t('consent')}</p>
          <Countdown deadline={o.confirmation_deadline} label={to('confirmBy')} />
          <div className="flex flex-wrap gap-2">
            <Button loading={confirm.state.kind === 'loading'} onClick={() => confirm.execute()}>{t('cta', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</Button>
            <Button variant="secondary" loading={decline.state.kind === 'loading'} onClick={() => decline.execute()}>{to('decline')}</Button>
          </div>
        </Card>
      ) : null}
      <CommandStatus state={decline.state} successText={tl('declined')} />
      {/* Outside the card: the card disappears once the order is confirmed, the outcome must stay visible. */}
      <CommandStatus state={confirm.state} successText={to('confirmed')} />
      {o.state === 'CONFIRMED' ? (
        <Card className="flex flex-col gap-2">
          <CardTitle>{to('confirmedAtMost', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</CardTitle>
          <CardText>{to('confirmedWhen', { at: ist(o.confirmed_at), close: ist(o.confirmation_deadline) })}</CardText>
          <Countdown deadline={o.confirmation_deadline} label={to('confirmationsClose')} />
        </Card>
      ) : null}
      {o.snapshots.final ? (
        <Card className="flex flex-col gap-1">
          <CardTitle>{to('finalPrice')}</CardTitle>
          <Price paise={o.snapshots.final.output.cash_payable_paise} size="lg" label={to('finalCash')} />
          <CardText>{to('ceilingWas', { ceiling: rupees(ceiling.output.cash_payable_paise) })} {o.snapshots.final.output.cash_payable_paise < ceiling.output.cash_payable_paise ? to('priceFell') : to('priceStayed')}</CardText>
        </Card>
      ) : null}
      {b && b.voucher_active && ['ISSUED', 'DEALER_CONFIRMED', 'DELIVERY_SCHEDULED'].includes(b.state) ? <VoucherCard orderId={o.id} /> : null}
      {b?.state === 'DELIVERY_SCHEDULED' ? (
        <Card emphasis className="flex flex-col gap-2">
          <CardTitle>{tl('deliveryOn', { date: b.scheduled_date ?? '', slot: b.delivery_slot ?? '' })}</CardTitle>
          <CardText>{tl('deliveryBody')}</CardText>
          <Button className="self-start" loading={delivered.state.kind === 'loading'} onClick={() => delivered.execute()}>{tl('confirmDelivery')}</Button>
          <CommandStatus state={delivered.state} successText={tl('deliveredDone')} />
        </Card>
      ) : null}
      {b?.overdue_flag && b.state !== 'DELIVERED' && b.state !== 'CANCELLED' ? (
        <p role="alert" className="rounded-lg bg-warning-bg p-3 text-sm">{tl('overdue', { date: b.promised_delivery_date ?? '' })}</p>
      ) : null}
      {b?.state === 'DELIVERED' ? (
        <Card className="flex flex-col gap-2">
          <CardTitle>{tl('deliveredTitle')}</CardTitle>
          <CardText>{tl('deliveredBody', { at: ist(b.delivered_at) })}</CardText>
          <ReviewForm bookingId={b.id} />
        </Card>
      ) : null}
      {['CANCELLED', 'DECLINED', 'VOIDED'].includes(o.state) ? (
        <Card className="flex flex-col gap-2">
          <CardTitle>{tl('endedTitle')}</CardTitle>
          <CardText>{tl(`ended.${o.cancel_reason ?? o.decline_reason ?? o.state}`)}</CardText>
          {dealerFailure ? <CardText>{tl('caseOpened')}</CardText> : null}
        </Card>
      ) : null}
      {o.disputes.length > 0 ? (
        <Card className="flex flex-col gap-2">
          <CardTitle>{tl('cases')}</CardTitle>
          <ul className="flex flex-col gap-1">
            {o.disputes.map((d) => (
              <li key={d.id}><Link className="underline" href={`/disputes/${d.id}`}>{tl(`category.${d.category}`)}</Link> · <Badge tone="neutral">{d.state.replace(/_/g, ' ').toLowerCase()}</Badge></li>
            ))}
          </ul>
        </Card>
      ) : null}
      {['BOOKED', 'COMPLETED', 'CANCELLED'].includes(o.state) ? (
        <details className="rounded-lg border border-border p-3" open={!!b?.overdue_flag}>
          <summary className="cursor-pointer font-semibold">{tl('problem')}</summary>
          <div className="mt-2"><DisputeForm order={o} /></div>
        </details>
      ) : null}
      {(o.state === 'CONFIRMED' || (o.state === 'BOOKED' && b && b.state !== 'DELIVERED')) ? (
        <div className="flex flex-col gap-2">
          {confirmCancel ? (
            <div className="flex flex-col gap-2 rounded-lg border border-border p-3" role="group" aria-label={to('cancelBooking')}>
              <p className="text-sm">{tl('cancelTerms')}</p>
              <div className="flex gap-2">
                <Button variant="secondary" loading={cancel.state.kind === 'loading'} onClick={() => cancel.execute()}>{tl('cancelYes')}</Button>
                <Button variant="link" onClick={() => setConfirmCancel(false)}>{tl('cancelNo')}</Button>
              </div>
            </div>
          ) : <Button variant="secondary" className="self-start" onClick={() => setConfirmCancel(true)}>{to('cancelBooking')}</Button>}
          <CommandStatus state={cancel.state} successText={tl('cancelledDone')} />
        </div>
      ) : null}
      <Breakdown output={(o.snapshots.final ?? ceiling).output} caption={o.snapshots.final ? to('finalBreakdown') : to('ceilingBreakdown')} />
      <p className="text-xs text-muted">{to('snapshot', { sha: (o.snapshots.final ?? ceiling).sha256.slice(0, 12), at: ist((o.snapshots.final ?? ceiling).computed_at) })}</p>
    </div>
  );
}
