import { CompareView } from '@/components/compare-view';

export default async function ComparePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <CompareView poolId={id} />;
}
