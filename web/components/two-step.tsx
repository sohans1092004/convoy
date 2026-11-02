'use client';
import * as React from 'react';
import { Button } from '@/components/ui/button';

/** A consequential action in two steps: the button opens the terms and an explicit yes; "no" is focused, so a stray Enter changes nothing. */
export function TwoStep({ label, terms, yes, no, onConfirm, loading, disabled, variant = 'secondary', className }: {
  label: string; terms: string; yes: string; no: string; onConfirm: () => void; loading?: boolean; disabled?: boolean; variant?: 'primary' | 'secondary'; className?: string;
}) {
  const [open, setOpen] = React.useState(false);
  const opener = React.useRef<HTMLButtonElement>(null);
  const wasOpen = React.useRef(false);
  // Focus goes back to the opener when "no" closes the terms, not to the top of the page.
  React.useEffect(() => { if (wasOpen.current && !open) opener.current?.focus(); wasOpen.current = open; }, [open]);
  if (!open) return <Button ref={opener} variant={variant} className={className} disabled={disabled} loading={loading} onClick={() => setOpen(true)}>{label}</Button>;
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2 rounded-lg border border-control p-3">
      <p className="text-sm">{terms}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" loading={loading} onClick={onConfirm}>{yes}</Button>
        <Button variant="link" autoFocus onClick={() => setOpen(false)}>{no}</Button>
      </div>
    </div>
  );
}
