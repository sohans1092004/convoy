import { getTranslations } from 'next-intl/server';
import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { ist } from '@/lib/format';
import type { Schemas } from '@/lib/api';
import { gatewayGet } from '@/lib/gateway';

type OrderRow = Schemas['order']['OrderRow'];

export default async function OrdersPage() {
  const t = await getTranslations('orders');
  const { data } = await gatewayGet<{ orders: OrderRow[] }>('/me/orders');
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('title')}</h1>
      {!data || data.orders.length === 0 ? (
        <Card><p>{t('none')}</p></Card>
      ) : (
        <ul className="flex flex-col gap-3">
          {data.orders.map((o) => (
            <li key={o.id}>
              <Card className="flex flex-wrap items-center gap-3">
                <Link href={`/orders/${o.id}`} className="font-semibold underline">Order {o.id.slice(-8)}</Link>
                <Badge tone={o.state === 'BOOKED' ? 'success' : o.state === 'CONFIRMATION_PENDING' ? 'warning' : 'neutral'}>{o.state.replace(/_/g, ' ').toLowerCase()}</Badge>
                {o.state === 'CONFIRMATION_PENDING' ? <span className="text-sm text-muted">Confirm by {ist(o.confirmation_deadline)}</span> : null}
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
