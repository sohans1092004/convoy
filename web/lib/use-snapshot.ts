'use client';
import * as React from 'react';
import { ApiError, type Problem } from './api';

/** Poll a snapshot endpoint every 10 s (realtime arrives in P2); refetch on demand after an action. */
export function useSnapshot<T>(load: () => Promise<{ data: T; response: Response }>, deps: unknown[], intervalMs = 10_000) {
  const [state, setState] = React.useState<{ data?: T; response?: Response; problem?: Problem; loading: boolean }>({ loading: true });
  const loadRef = React.useRef(load);
  loadRef.current = load;
  const refresh = React.useCallback(async () => {
    try {
      const r = await loadRef.current();
      setState({ data: r.data, response: r.response, loading: false });
    } catch (e) {
      setState((s) => ({ ...s, loading: false, problem: e instanceof ApiError ? e.problem : { status: 0, detail: 'Network error; retrying.' } }));
    }
  }, []);
  React.useEffect(() => {
    refresh();
    const t = setInterval(refresh, intervalMs);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { ...state, refresh };
}
