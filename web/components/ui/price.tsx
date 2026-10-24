import { cn } from '@/lib/cn';
import { rupees, type Evidence } from '@/lib/format';
import { EvidenceBadge } from './badge';

/** A rupee amount from integer paise (lakh grouping) with its evidence label. */
export function Price({ paise, evidence, size = 'md', className, label }: {
  paise: number;
  evidence?: Evidence;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  /** Accessible name when the visual context is not enough, e.g. "Cash payable at your ceiling". */
  label?: string;
}) {
  return (
    <span className={cn('inline-flex flex-wrap items-baseline gap-2', className)}>
      <span aria-label={label ? `${label}: ${rupees(paise)}` : undefined}
        className={cn('tabular font-bold', size === 'lg' && 'text-3xl', size === 'md' && 'text-xl', size === 'sm' && 'text-base')}>
        {rupees(paise)}
      </span>
      {evidence ? <EvidenceBadge evidence={evidence} /> : null}
    </span>
  );
}
