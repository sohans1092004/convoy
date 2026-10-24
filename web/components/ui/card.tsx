import * as React from 'react';
import { cn } from '@/lib/cn';

export function Card({ className, emphasis, ...props }: React.HTMLAttributes<HTMLDivElement> & { emphasis?: boolean }) {
  return <div className={cn('rounded-lg border border-border bg-surface p-4 sm:p-5', emphasis && 'border-2 border-primary', className)} {...props} />;
}

export function CardTitle({ className, as: Tag = 'h2', ...props }: React.HTMLAttributes<HTMLHeadingElement> & { as?: 'h2' | 'h3' }) {
  return <Tag className={cn('text-lg font-bold leading-snug', className)} {...props} />;
}

export function CardText({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-sm text-muted', className)} {...props} />;
}
