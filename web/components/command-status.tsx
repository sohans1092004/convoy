import { ist } from '@/lib/format';
import type { CommandState } from '@/lib/use-command';
import { cn } from '@/lib/cn';
import { useTranslations } from 'next-intl';

/** Inline status for an action: the same five states everywhere (frontend-quality.md). */
export function CommandStatus({ state, successText, className }: { state: CommandState; successText?: string; className?: string }) {
  const t = useTranslations('status');
  if (state.kind === 'idle' || state.kind === 'loading') return <div aria-live="polite" className={className} />;
  if ((state.kind === 'error' || state.kind === 'conflict') && state.problem.code === 'NOT_IMPLEMENTED_IN_P1') {
    // Honest UI (P2 step 0.7): the rule is real and shown on the page, but this test build cannot carry it out yet.
    return (
      <div aria-live="polite" role="status" className={cn('rounded-lg bg-info-bg p-3 text-sm text-info-fg', className)}>
        <p className="font-semibold">{t('notBuiltTitle')}</p>
        <p>{state.problem.detail}</p>
        <p className="mt-1">{t('notBuiltBody')}</p>
      </div>
    );
  }
  const tone = {
    pending: 'bg-info-bg text-info-fg',
    error: 'bg-danger-bg text-danger',
    conflict: 'bg-warning-bg text-warning-fg',
    success: 'bg-success-bg text-success-fg',
  }[state.kind];
  return (
    <div aria-live="polite" role={state.kind === 'error' || state.kind === 'conflict' ? 'alert' : 'status'} className={cn('rounded-lg p-3 text-sm', tone, className)}>
      {state.kind === 'pending' ? <p>{state.message}</p> : null}
      {state.kind === 'success' ? <p className="font-semibold">{state.message ?? successText ?? 'Done.'}</p> : null}
      {state.kind === 'error' || state.kind === 'conflict' ? (
        <>
          <p className="font-semibold">{state.kind === 'conflict' ? 'This changed before your action reached us' : 'That did not work'}</p>
          <p>{state.problem.detail ?? state.problem.title}</p>
          {state.problem.code === 'DEADLINE_PASSED' && state.problem.deadline ? (
            <p className="mt-1">Deadline {ist(state.problem.deadline, true)}; our database clock read {ist(state.problem.server_time as string, true)}. Nothing was saved.</p>
          ) : null}
          {state.kind === 'error' ? <p className="mt-1">{t('inputKept')}</p> : <p className="mt-1">{t('latestState')}</p>}
        </>
      ) : null}
    </div>
  );
}
