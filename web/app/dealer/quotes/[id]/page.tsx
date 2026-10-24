import { QuoteForm } from '@/components/quote-form';

export default async function DealerQuotePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <QuoteForm quoteId={id} />;
}
