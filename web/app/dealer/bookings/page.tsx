import { cookies } from 'next/headers';
import { DealerBookings } from '@/components/dealer-bookings';

export default async function DealerBookingsPage() {
  const roles = ((await cookies()).get('cv_roles')?.value ?? '').split(',');
  return <DealerBookings admin={roles.includes('dealer_admin')} />;
}
