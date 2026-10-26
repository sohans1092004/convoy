import { DisputeView } from '@/components/dispute-view';

export default async function DisputePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DisputeView disputeId={id} />;
}
