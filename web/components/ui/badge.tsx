import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { cn } from '@/lib/cn';
import type { Evidence } from '@/lib/format';
import { useTranslations } from 'next-intl';

const badgeVariants = cva('inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-semibold', {
  variants: {
    tone: {
      neutral: 'border-border bg-surface-2 text-fg',
      success: 'border-transparent bg-success-bg text-success-fg',
      warning: 'border-transparent bg-warning-bg text-warning-fg',
      danger: 'border-transparent bg-danger-bg text-danger',
      info: 'border-transparent bg-info-bg text-info-fg',
    },
  },
  defaultVariants: { tone: 'neutral' },
});

export function Badge({ className, tone, ...props }: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}

const EVIDENCE_MARK: Record<Evidence, string> = { DEALER_CONFIRMED: '■', CATALOGUE_ESTIMATE: '▣', UNVERIFIED: '□' };

/** Evidence level of a price line: a small word beside the amount, shape + text, never colour alone. Only "Unverified" takes colour. */
export function EvidenceBadge({ evidence, className }: { evidence: Evidence; className?: string }) {
  const t = useTranslations('evidence');
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap text-xs', evidence === 'UNVERIFIED' ? 'font-semibold text-warning-fg' : 'text-muted', className)}>
      <span aria-hidden>{EVIDENCE_MARK[evidence]}</span>
      {t(evidence)}
    </span>
  );
}
