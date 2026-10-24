import { PoolView } from '@/components/pool-view';

export default async function PoolPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <PoolView poolId={id} checkoutUrl={process.env.RAZORPAY_CHECKOUT_URL || 'http://localhost:9090/checkout.js'} />;
}
