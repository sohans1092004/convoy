'use client';
import { useTranslations } from 'next-intl';
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
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

/** snapshots: "ceiling" once the order exists, "final" once bookings are issued. */
type Voucher = Schemas['order']['Voucher'];

function VoucherCard({ orderId }: { orderId: string }) {
  const [v, setV] = React.useState<Voucher | null>(null);
  const [svg, setSvg] = React.useState('');
  React.useEffect(() => {
    call(api.GET('/orders/{id}/voucher', { params: { path: { id: orderId } } })).then(async (r) => {
      setV(r.data);
      setSvg(await QRCode.toString(r.data.qr_payload, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }));
    }).catch(() => {});
  }, [orderId]);
  if (!v) return <Skeleton className="h-64" label="Loading your voucher" />;
  return (
    <Card emphasis className="flex flex-col items-start gap-3 sm:flex-row">
      <div role="img" aria-label={`QR code for booking voucher ${v.id.slice(-8)}`} className="w-48 shrink-0 rounded-lg bg-white p-2" dangerouslySetInnerHTML={{ __html: svg }} />
      <div className="flex flex-col gap-1">
        <CardTitle>Booking voucher</CardTitle>
        <p className="text-sm">Booking <span className="font-mono">{v.id.slice(-8).toUpperCase()}</span> · <Badge tone="success">{v.state.replace(/_/g, ' ').toLowerCase()}</Badge></p>
        {v.promised_delivery_date ? <p className="text-sm">Promised delivery from {v.promised_delivery_date}</p> : null}
        <CardText>Show this QR code at the dealership. It carries a signed token the dealer scans to check your booking. TEST MODE: no real money.</CardText>
      </div>
    </Card>
  );
}

export function OrderView({ orderId }: { orderId: string }) {
  const t = useTranslations('confirm');
  const snap = useSnapshot(() => call(api.GET('/orders/{id}', { params: { path: { id: orderId } } })), [orderId]);
  const confirm = useCommand(async (key: string) => {
    const fresh = await call(api.GET('/orders/{id}', { params: { path: { id: orderId } } }));
    await call(api.POST('/orders/{id}/confirm', {
      params: { path: { id: orderId } }, body: { ceiling_snapshot_hash: fresh.data.snapshots.ceiling!.sha256 },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${etagVersion(fresh.response)}"` },
    }));
    await snap.refresh();
  });
  // Decline (ORD-T03) and cancel (ORD-T04) are specified but still answer 501; the attempt explains that honestly.
  const decline = useCommand(async () => call(api.POST('/orders/{id}/decline', { params: { path: { id: orderId } } })));
  const cancel = useCommand(async () => call(api.POST('/orders/{id}/cancel', { params: { path: { id: orderId } } })));
  const o = snap.data;
  if (snap.loading && !o) return <Skeleton className="h-96" label="Loading your order" />;
  if (!o) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{snap.problem?.detail ?? 'This order could not be loaded.'}</p>;
  const ceiling = o.snapshots.ceiling!;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">Your order</h1>
        <Badge tone={o.state === 'BOOKED' ? 'success' : o.state === 'CONFIRMATION_PENDING' ? 'warning' : 'neutral'}>{o.state.replace(/_/g, ' ').toLowerCase()}</Badge>
      </div>
      {o.state === 'CONFIRMATION_PENDING' ? (
        <Card emphasis className="flex flex-col gap-3">
          <CardTitle>{t('title', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</CardTitle>
          <p className="text-sm">{t('body', { ceiling: rupees(ceiling.output.cash_payable_paise) })}</p>
          <Countdown deadline={o.confirmation_deadline} label="Confirm by" />
          <div className="flex flex-wrap gap-2">
            <Button loading={confirm.state.kind === 'loading'} onClick={() => confirm.execute()}>Confirm at most {rupees(ceiling.output.cash_payable_paise)}</Button>
            <Button variant="secondary" loading={decline.state.kind === 'loading'} onClick={() => decline.execute()}>Decline this offer</Button>
          </div>
        </Card>
      ) : null}
      <CommandStatus state={decline.state} />
      {/* Outside the card: the card disappears once the order is confirmed, the outcome must stay visible. */}
      <CommandStatus state={confirm.state} successText="Confirmed. Your final price is set when confirmations close; it can only be the same or lower." />
      {o.state === 'CONFIRMED' ? (
        <Card className="flex flex-col gap-2">
          <CardTitle>Confirmed at most {rupees(ceiling.output.cash_payable_paise)}</CardTitle>
          <CardText>Confirmed {ist(o.confirmed_at)}. The final price is set when confirmations close on {ist(o.confirmation_deadline)}.</CardText>
          <Countdown deadline={o.confirmation_deadline} label="Confirmations close" />
        </Card>
      ) : null}
      {o.state === 'BOOKED' && o.snapshots.final ? (
        <>
          <Card className="flex flex-col gap-1">
            <CardTitle>Final price</CardTitle>
            <Price paise={o.snapshots.final.output.cash_payable_paise} size="lg" label="Final cash payable" />
            <CardText>Your ceiling was {rupees(ceiling.output.cash_payable_paise)}. {o.snapshots.final.output.cash_payable_paise < ceiling.output.cash_payable_paise ? 'More buyers confirmed, so your price fell.' : 'The price stayed at your ceiling.'}</CardText>
          </Card>
          <VoucherCard orderId={o.id} />
        </>
      ) : null}
      {o.state === 'CONFIRMED' || o.state === 'BOOKED' ? (
        <div className="flex flex-col gap-2">
          <Button variant="secondary" className="self-start" loading={cancel.state.kind === 'loading'} onClick={() => cancel.execute()}>Cancel my booking</Button>
          <CommandStatus state={cancel.state} />
        </div>
      ) : null}
      <Breakdown output={(o.snapshots.final ?? ceiling).output} caption={o.snapshots.final ? 'Final price breakdown' : 'Breakdown at your ceiling'} />
      <p className="text-xs text-muted">Snapshot {(o.snapshots.final ?? ceiling).sha256.slice(0, 12)} · calculated {ist((o.snapshots.final ?? ceiling).computed_at)}</p>
    </div>
  );
}
