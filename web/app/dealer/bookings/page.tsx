import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { ist, rupees } from '@/lib/format';
import { gatewayGet } from '@/lib/gateway';

interface Booking { id: string; order_id: string; state: string; promised_delivery_date: string | null; dealer_confirm_deadline: string; final_cash_payable_paise: number }

export default async function DealerBookings() {
  const { data } = await gatewayGet<{ bookings: Booking[] }>('/dealer/bookings');
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">Bookings</h1>
      {!data || data.bookings.length === 0 ? <Card><p>No bookings yet.</p></Card> : (
        <ul className="flex flex-col gap-3">
          {data.bookings.map((b) => (
            <li key={b.id}>
              <Card className="flex flex-wrap items-center gap-3">
                <span className="font-mono font-semibold">{b.id.slice(-8).toUpperCase()}</span>
                <Badge tone={b.state === 'ISSUED' ? 'warning' : 'success'}>{b.state.replace(/_/g, ' ').toLowerCase()}</Badge>
                <span className="tabular">{rupees(b.final_cash_payable_paise)}</span>
                <span className="text-sm text-muted">Confirm by {ist(b.dealer_confirm_deadline)}</span>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
