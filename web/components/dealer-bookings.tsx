'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist, rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

type Booking = Schemas['order']['DealerBooking'];

const todayIst = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());

function BookingRow({ b, admin, refresh }: { b: Booking; admin: boolean; refresh: () => Promise<unknown> }) {
  const t = useTranslations('dealerBookings');
  const [date, setDate] = React.useState(b.scheduled_date ?? todayIst());
  const [slot, setSlot] = React.useState(b.delivery_slot ?? '10:00-12:00');
  const [token, setToken] = React.useState('');
  const [email, setEmail] = React.useState<string | null>(null);
  const run = (fn: (key: string) => Promise<unknown>) => async (key: string) => { await fn(key); await refresh(); };
  const confirm = useCommand(run((key) => call(api.POST('/dealer/bookings/{id}/confirm', { params: { path: { id: b.id } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${b.version}"` } }))));
  const schedule = useCommand(run((key) => call(api.PUT('/dealer/bookings/{id}/delivery', {
    params: { path: { id: b.id } }, body: { scheduled_date: date, slot }, headers: { 'Idempotency-Key': key, 'If-Match': `"${b.version}"` } }))));
  const deliver = useCommand(run((key) => call(api.POST('/dealer/bookings/{id}/deliver', { params: { path: { id: b.id } }, body: { voucher_token: token.trim() }, headers: { 'Idempotency-Key': key } }))));
  const cancel = useCommand(run((key) => call(api.POST('/dealer/bookings/{id}/cancel', {
    params: { path: { id: b.id } }, body: { reason: 'DEALER_CANCELLED' }, headers: { 'Idempotency-Key': key, 'If-Match': `"${b.version}"` } }))));
  const contact = useCommand(async () => setEmail((await call(api.GET('/dealer/bookings/{id}/contact', { params: { path: { id: b.id } } }))).data.email));
  const open = !['DELIVERED', 'CANCELLED'].includes(b.state);
  return (
    <Card className="flex flex-col gap-3" aria-label={t('booking', { id: b.id.slice(-8).toUpperCase() })}>
      <div className="flex flex-wrap items-center gap-3">
        <span className="font-mono font-semibold">{b.id.slice(-8).toUpperCase()}</span>
        <Badge tone={b.state === 'ISSUED' ? 'warning' : b.state === 'CANCELLED' ? 'neutral' : 'success'}>{b.state.replace(/_/g, ' ').toLowerCase()}</Badge>
        <span className="tabular">{rupees(b.final_cash_payable_paise)}</span>
        {b.overdue_flag ? <Badge tone="warning">{t('overdue')}</Badge> : null}
      </div>
      {b.state === 'ISSUED' ? (
        <div className="flex flex-col gap-2">
          <Countdown deadline={b.dealer_confirm_deadline} label={t('confirmBy')} />
          <Button className="self-start" loading={confirm.state.kind === 'loading'} onClick={() => confirm.execute()}>{t('confirm')}</Button>
          <CommandStatus state={confirm.state} />
        </div>
      ) : null}
      {['DEALER_CONFIRMED', 'DELIVERY_SCHEDULED'].includes(b.state) ? (
        <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); schedule.execute(); }}>
          <label className="flex flex-col gap-1 text-sm">{t('date')}<input type="date" min={todayIst()} required className="rounded-lg border border-border bg-surface p-2" value={date} onChange={(e) => setDate(e.target.value)} /></label>
          <label className="flex flex-col gap-1 text-sm">{t('slot')}<input className="rounded-lg border border-border bg-surface p-2" maxLength={40} value={slot} onChange={(e) => setSlot(e.target.value)} /></label>
          <Button type="submit" variant="secondary" loading={schedule.state.kind === 'loading'}>{b.state === 'DELIVERY_SCHEDULED' ? t('reschedule') : t('setDate')}</Button>
          <CommandStatus state={schedule.state} className="basis-full" />
        </form>
      ) : null}
      {b.scheduled_date ? <CardText>{t('scheduled', { date: b.scheduled_date, slot: b.delivery_slot ?? '' })}</CardText> : null}
      {b.state === 'DELIVERY_SCHEDULED' ? (
        <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); deliver.execute(); }}>
          <label className="flex min-w-0 flex-1 flex-col gap-1 text-sm">{t('voucher')}<input required className="rounded-lg border border-border bg-surface p-2 font-mono text-xs" value={token} onChange={(e) => setToken(e.target.value)} /></label>
          <Button type="submit" loading={deliver.state.kind === 'loading'}>{t('handOver')}</Button>
          <CommandStatus state={deliver.state} className="basis-full" />
        </form>
      ) : null}
      {b.state === 'DELIVERED' ? <CardText>{t('delivered', { at: ist(b.delivered_at) })}</CardText> : null}
      {b.state === 'CANCELLED' ? <CardText>{t('cancelled', { reason: b.cancel_reason ?? '' })}</CardText> : null}
      {b.contact_released ? (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {email ? <span>{t('contact')}: <a className="underline" href={`mailto:${email}`}>{email}</a></span>
            : <Button variant="link" loading={contact.state.kind === 'loading'} onClick={() => contact.execute()}>{t('showContact')}</Button>}
          <CommandStatus state={contact.state} />
        </div>
      ) : null}
      {admin && open ? (
        <details className="text-sm"><summary className="cursor-pointer">{t('cancelTitle')}</summary>
          <p className="my-2">{t('cancelTerms')}</p>
          <Button variant="secondary" loading={cancel.state.kind === 'loading'} onClick={() => cancel.execute()}>{t('cancel')}</Button>
          <CommandStatus state={cancel.state} />
        </details>
      ) : null}
    </Card>
  );
}

export function DealerBookings({ admin }: { admin: boolean }) {
  const t = useTranslations('dealerBookings');
  const snap = useSnapshot(() => call(api.GET('/dealer/bookings')), [], 10_000);
  if (snap.loading && !snap.data) return <Skeleton className="h-48" label={t('loading')} />;
  const list = snap.data?.bookings ?? [];
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('title')}</h1>
      {list.length === 0 ? <Card><p>{t('empty')}</p></Card> : (
        <ul className="flex flex-col gap-3">{list.map((b) => <li key={b.id}><BookingRow b={b} admin={admin} refresh={snap.refresh} /></li>)}</ul>
      )}
    </div>
  );
}
