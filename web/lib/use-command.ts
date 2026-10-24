'use client';
import * as React from 'react';
import { ApiError, type Problem } from './api';

export type CommandState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'pending'; message: string }
  | { kind: 'error'; problem: Problem }
  | { kind: 'conflict'; problem: Problem }
  | { kind: 'success'; message?: string };

const CONFLICT_CODES = new Set(['DEADLINE_PASSED', 'VERSION_CONFLICT', 'INVALID_TRANSITION', 'ALREADY_MEMBER', 'CAPACITY_REACHED', 'ACTIVE_COMMITMENT_EXISTS', 'QUOTE_NOT_FINAL']);

/**
 * One user action (join, commit, confirm, submit). A click intent gets one Idempotency-Key, reused
 * when the user retries after an error, so double clicks and retries never create a second effect.
 * 409s map to the conflict state; other errors keep the user's input and show the problem detail.
 */
export function useCommand<A extends unknown[], R>(run: (idempotencyKey: string, ...args: A) => Promise<R>) {
  const [state, setState] = React.useState<CommandState>({ kind: 'idle' });
  const key = React.useRef<string | null>(null);
  const inFlight = React.useRef(false);

  const execute = React.useCallback(async (...args: A): Promise<R | undefined> => {
    if (inFlight.current) return undefined; // a second click while the first is running is ignored
    inFlight.current = true;
    key.current ??= crypto.randomUUID();
    setState({ kind: 'loading' });
    try {
      const r = await run(key.current, ...args);
      key.current = null;
      setState({ kind: 'success' });
      return r;
    } catch (e) {
      const problem: Problem = e instanceof ApiError ? e.problem : { status: 0, title: 'Network error', detail: 'Check your connection and try again. Nothing was lost.' };
      if (problem.status === 409 || CONFLICT_CODES.has(String(problem.code))) {
        key.current = null; // the server state moved on; a new attempt is a new intent
        setState({ kind: 'conflict', problem });
      } else {
        setState({ kind: 'error', problem }); // keep the key: retrying is the same intent
      }
      return undefined;
    } finally {
      inFlight.current = false;
    }
  }, [run]);

  return { state, execute, setState, reset: React.useCallback(() => setState({ kind: 'idle' }), []) };
}
