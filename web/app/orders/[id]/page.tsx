import { OrderView } from '@/components/order-view';

export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <OrderView orderId={id} />;
}
