'use client';
import * as React from 'react';

/**
 * P7.D: the small part of TanStack Query this app used (discover, model estimate, live pool snapshot), without its
 * ~17 KB runtime on every route. Per-key cache for the tab's lifetime (returning to a key shows its last data while it
 * refreshes); while a new key loads, the previous key's data stays visible and isPlaceholderData is true.
 */
type Entry = { data?: unknown; updatedAt: number };
const cache = new Map<string, Entry>();
/** Storybook: each story starts empty. */
export function clearFetchCache() { cache.clear(); }

export interface FetchState<T, E> {
  data: T | undefined;
  error: E | null;
  isPending: boolean; // no data yet for this key and none to show
  isFetching: boolean;
  isError: boolean;
  isPlaceholderData: boolean; // showing another key's data while this one loads
  dataUpdatedAt: number;
  refetch: () => Promise<void>;
  setData: (f: (old: T | undefined) => T | undefined) => void;
}

export function useFetch<T, E = Error>(key: readonly unknown[], fn: () => Promise<T>, opts: {
  enabled?: boolean; refetchInterval?: number | false; retry?: (failures: number, error: E) => boolean;
} = {}): FetchState<T, E> {
  const id = JSON.stringify(key);
  const enabled = opts.enabled ?? true;
  const fnRef = React.useRef(fn);
  const retryRef = React.useRef(opts.retry);
  // eslint-disable-next-line react-hooks/refs -- latest-callback refs: a fetch always uses the current loader and retry rule
  fnRef.current = fn; retryRef.current = opts.retry;
  const [, render] = React.useReducer((n: number) => n + 1, 0);
  const [error, setError] = React.useState<E | null>(null);
  const [fetching, setFetching] = React.useState(false);
  const [shown, setShown] = React.useState<{ id: string; data: unknown } | null>(null);
  const inFlight = React.useRef<Promise<void> | null>(null);

  const refetch = React.useCallback(() => {
    if (inFlight.current) return inFlight.current; // never two loads of one key at once
    const run = async () => {
      setFetching(true);
      for (let failures = 0; ; failures++) {
        try {
          const data = await fnRef.current();
          cache.set(id, { data, updatedAt: Date.now() });
          setError(null);
          break;
        } catch (e) {
          const err = e as E;
          if (!(retryRef.current ?? ((n: number) => n < 1))(failures, err)) { setError(err); break; }
        }
      }
      setFetching(false);
      render();
    };
    inFlight.current = run().finally(() => { inFlight.current = null; });
    return inFlight.current;
  }, [id]);

  React.useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new key starts with no error of its own
    setError(null);
    if (enabled) void refetch();
  }, [id, enabled, refetch]);
  React.useEffect(() => {
    if (!enabled || !opts.refetchInterval) return;
    const t = setInterval(() => void refetch(), opts.refetchInterval);
    return () => clearInterval(t);
  }, [enabled, opts.refetchInterval, refetch]);

  const setData = React.useCallback((f: (old: T | undefined) => T | undefined) => {
    cache.set(id, { data: f(cache.get(id)?.data as T | undefined), updatedAt: Date.now() });
    render();
  }, [id]);

  const own = cache.get(id);
  // the last key that had data, for the placeholder (state derived during render, as React documents)
  if (own?.data !== undefined && (shown?.id !== id || shown.data !== own.data)) setShown({ id, data: own.data });
  const placeholder = own?.data === undefined && shown !== null && shown.id !== id;
  const data = (own?.data ?? (placeholder ? shown!.data : undefined)) as T | undefined;
  return {
    data, error, isFetching: fetching, isError: error !== null, isPlaceholderData: placeholder,
    isPending: data === undefined && error === null, dataUpdatedAt: own?.updatedAt ?? 0, refetch, setData,
  };
}
