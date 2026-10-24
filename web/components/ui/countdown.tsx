'use client';
import * as React from 'react';
import { cn } from '@/lib/cn';
import { ist } from '@/lib/format';
import { serverNow } from '@/lib/server-clock';

function parts(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return { d: Math.floor(s / 86400), h: Math.floor((s % 86400) / 3600), m: Math.floor((s % 3600) / 60), s: s % 60 };
}

/**
 * Countdown to a deadline using server time (offset from Server-Time headers, deadline-rule.md §3).
 * Never pre-rejects: in the last 60 s it explains that the database clock decides.
 */
export function Countdown({ deadline, label, className, now }: { deadline: string; label: string; className?: string; now?: () => number }) {
  const clock = now ?? serverNow;
  const [left, setLeft] = React.useState(() => Date.parse(deadline) - clock());
  React.useEffect(() => {
    const t = setInterval(() => setLeft(Date.parse(deadline) - clock()), 1000);
    return () => clearInterval(t);
  }, [deadline, clock]);
  const p = parts(left);
  const text = left <= 0 ? 'Closed' : p.d > 0 ? `${p.d} d ${p.h} h ${p.m} m` : `${String(p.h).padStart(2, '0')}:${String(p.m).padStart(2, '0')}:${String(p.s).padStart(2, '0')}`;
  return (
    <div className={cn('text-sm', className)}>
      <p>
        <span className="font-semibold">{label}</span> {ist(deadline)} ·{' '}
        <span className="tabular font-bold" aria-live="off">{text}</span>
      </p>
      <p className="text-muted">Server time {ist(new Date(clock()).toISOString(), true)}</p>
      {left > 0 && left <= 60_000 ? (
        <p className="mt-1 font-medium text-warning-fg">Actions must reach our database before {ist(deadline, true)}. A slow network can make a last-second action miss.</p>
      ) : null}
    </div>
  );
}
