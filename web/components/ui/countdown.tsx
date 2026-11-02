'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { cn } from '@/lib/cn';
import { ist } from '@/lib/format';
import { serverNow } from '@/lib/server-clock';

/** The deadline's one change of meaning: open, its last minute, closed. */
export const deadlinePhase = (msLeft: number) => (msLeft <= 0 ? 'closed' : msLeft <= 60_000 ? 'warning' : 'open');

/**
 * A deadline stated as a fact: its date and time, and the time now on the server clock (deadline-rule.md §3), to the minute.
 * Never pre-rejects. The live region's text changes once at the last minute and once at the close, so each is announced once.
 */
export function Countdown({ deadline, label, className, now }: { deadline: string; label: string; className?: string; now?: () => number }) {
  const t = useTranslations('countdown');
  const clock = now ?? serverNow;
  const [phase, setPhase] = React.useState(() => deadlinePhase(Date.parse(deadline) - clock()));
  const [time, setTime] = React.useState(() => ist(new Date(clock()).toISOString()));
  React.useEffect(() => {
    const tick = () => { // both setters bail out when the value is unchanged, so this renders about once a minute
      setPhase(deadlinePhase(Date.parse(deadline) - clock()));
      setTime(ist(new Date(clock()).toISOString()));
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [deadline, clock]);
  const message = phase === 'open' ? '' : phase === 'closed' ? t('closed') : t('warning', { time: ist(deadline, true) });
  return (
    <div className={cn('text-sm', className)}>
      <p><span className="font-semibold">{label}</span> <span className="tabular">{ist(deadline)}</span></p>
      {phase === 'open' ? <p className="text-muted">{t('now', { time })}</p> : <p aria-hidden className="mt-1">{message}</p>}
      <p role="status" className="sr-only">{message}</p>
    </div>
  );
}
