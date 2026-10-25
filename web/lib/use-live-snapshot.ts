'use client';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as React from 'react';
import { ApiError, type Problem } from './api';
import { recordServerTime } from './server-clock';

export type LiveStatus = 'live' | 'reconnecting' | 'delayed';

interface Versioned { version: number }
interface StreamMessage { aggregate: string; id: string; version: number; event_type?: string; delta?: Record<string, unknown>; server_time?: string; resync?: boolean }

/** Merge a delta into the snapshot (deltas are hints carrying absolute values; the snapshot is the truth, realtime-recovery.md §2). */
export function applyDelta<T extends Versioned>(snap: T, msg: StreamMessage): T {
  return { ...snap, ...(msg.delta ?? {}), version: msg.version };
}

/**
 * Events whose whole effect on the snapshot is the absolute counts in their delta. Every other event changes nested
 * state the delta cannot carry (rounds, the buyer's own membership, a state reached as a side effect such as
 * RoundClosed -> DECISION_OPEN), so it refetches the snapshot instead of merging.
 */
const IN_PLACE = new Set(['MemberJoined', 'MemberLeft']);

/**
 * What to do with a stream message given the local version (pure, unit-tested):
 * ignore version <= local; apply version == local + 1 when the event is a counts-only one; anything else refetches.
 */
export function decide(local: number | undefined, incoming: number, eventType?: string): 'apply' | 'ignore' | 'resync' {
  if (local === undefined) return 'resync';
  if (incoming <= local) return 'ignore';
  return incoming === local + 1 && eventType !== undefined && IN_PLACE.has(eventType) ? 'apply' : 'resync';
}

/** Full-jitter exponential backoff: random(0, min(cap, base * 2^attempt)). */
export function backoffMs(attempt: number, base = 500, cap = 30_000): number {
  return Math.random() * Math.min(cap, base * 2 ** attempt);
}

/** Never let an older snapshot replace a newer one (a slow refetch must not undo a delta already applied). */
export function newer<T extends Versioned>(current: T | undefined, incoming: T): T {
  return current === undefined || incoming.version >= current.version ? incoming : current;
}

/**
 * Snapshot + SSE on the TanStack Query cache (realtime-recovery.md §4). On connect: load the owner's snapshot, then apply
 * only newer versions. A gap, a resync, or a heartbeat that knows a newer version refetches. While not live (stream down,
 * not allowed, reconnecting) the snapshot is polled every 10 s, so correctness never depends on the stream. Optimistic
 * updates go through setData and never bump the version, so the stream's own delta for that action applies cleanly
 * (its counts are absolute: the buyer's action is never counted twice).
 */
export function useLiveSnapshot<T extends Versioned>(key: readonly unknown[], load: () => Promise<{ data: T; response: Response }>, streamUrl: string | null) {
  const qc = useQueryClient();
  const [status, setStatus] = React.useState<LiveStatus>('reconnecting');
  const loadRef = React.useRef(load);
  loadRef.current = load;
  const query = useQuery<T, ApiError>({
    queryKey: key,
    queryFn: async () => newer(qc.getQueryData<T>(key), (await loadRef.current()).data),
    refetchInterval: status === 'live' ? false : 10_000,
  });
  // Never cancels a refetch in flight: a flapping stream must not restart it forever and hide its failure.
  const refresh = React.useCallback(() => qc.invalidateQueries({ queryKey: key }, { cancelRefetch: false }), [qc, key]);
  const setData = React.useCallback((f: (old: T) => T) => qc.setQueryData<T>(key, (old) => (old ? f(old) : old)), [qc, key]);
  const accept = React.useCallback((data: T) => qc.setQueryData<T>(key, (old) => newer(old, data)), [qc, key]);

  React.useEffect(() => {
    if (!streamUrl) {
      setStatus('delayed');
      return;
    }
    let closed = false;
    let es: EventSource | null = null;
    let attempt = 0;
    let lastEventAt = Date.now();
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let delayedTimer: ReturnType<typeof setTimeout> | undefined;
    let reconnectNow: (() => void) | undefined;
    // Watchdog: heartbeats come every 15 s, so 20 s of silence means the instance died without closing the stream.
    const watchdog = setInterval(() => {
      if (es && Date.now() - lastEventAt > 20_000) reconnectNow?.();
    }, 5_000);

    const onChanged = (ev: MessageEvent) => {
      const msg = JSON.parse(ev.data) as StreamMessage;
      if (msg.server_time) recordServerTime(msg.server_time, Date.now(), Date.now());
      const local = qc.getQueryData<T>(key)?.version;
      const d = decide(local, msg.version, msg.event_type);
      if (d === 'apply') qc.setQueryData<T>(key, (old) => (old ? applyDelta(old, msg) : old));
      else if (d === 'resync') refresh();
    };

    const connect = () => {
      if (closed) return;
      es = new EventSource(streamUrl);
      lastEventAt = Date.now();
      const seen = () => { lastEventAt = Date.now(); };
      // The backoff resets only once the stream proves healthy (a heartbeat or a message), not on connect: a stream
      // that connects and drops at once keeps backing off.
      const healthy = () => { attempt = 0; };
      es.addEventListener('heartbeat', healthy);
      es.addEventListener('pool.changed', healthy);
      for (const name of ['ready', 'pool.changed', 'resync', 'heartbeat']) es.addEventListener(name, seen);
      es.addEventListener('ready', () => {
        clearTimeout(delayedTimer);
        setStatus('live');
        refresh(); // snapshot after connecting, then only newer versions
      });
      es.addEventListener('pool.changed', onChanged as EventListener);
      es.addEventListener('resync', () => refresh());
      es.addEventListener('heartbeat', (ev) => {
        const hb = JSON.parse((ev as MessageEvent).data) as StreamMessage;
        if (hb.server_time) recordServerTime(hb.server_time, Date.now(), Date.now());
        const local = qc.getQueryData<T>(key)?.version;
        if (local !== undefined && hb.version > local) refresh(); // missed a delta (e.g. Redis down)
      });
      const reconnect = () => {
        es?.close();
        es = null;
        if (closed) return;
        setStatus('reconnecting');
        // Still not live after 10 s: say so honestly (polling keeps the page correct meanwhile).
        clearTimeout(delayedTimer);
        delayedTimer = setTimeout(() => setStatus((s) => (s === 'live' ? s : 'delayed')), 10_000);
        retryTimer = setTimeout(connect, backoffMs(attempt++));
      };
      reconnectNow = reconnect;
      es.addEventListener('overloaded', reconnect);
      es.addEventListener('closed', reconnect);
      es.onerror = reconnect;
    };
    connect();
    return () => {
      closed = true;
      es?.close();
      clearInterval(watchdog);
      clearTimeout(retryTimer);
      clearTimeout(delayedTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streamUrl]);

  const problem: Problem | undefined = query.error ? query.error.problem ?? { status: 0, detail: 'Network error; retrying.' } : undefined;
  return { data: query.data, loading: query.isPending, problem, updatedAt: query.dataUpdatedAt, fetching: query.isFetching, status, refresh, setData, accept };
}
