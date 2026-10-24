import { cn } from '@/lib/cn';

export function Skeleton({ className, label = 'Loading' }: { className?: string; label?: string }) {
  return <div role="status" aria-label={label} className={cn('animate-pulse rounded-lg bg-surface-2', className)} />;
}
