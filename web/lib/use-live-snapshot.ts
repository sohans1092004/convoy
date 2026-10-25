'use client';
import * as React from 'react';
import { ApiError, type Problem } from './api';
import { recordServerTime } from './server-clock';

export type LiveStatus = 'live' | 'reconnecting' | 'delayed';

interface Versioned { version: number }
interface StreamMessage { aggregate: string; id: string; version: number; delta?: Record<string, unknown>; server_time?: string; resync?: boolean }

/** Merge a delta into the snapshot (deltas are hints; the snapshot is the truth, realtime-recovery.md §2). */
export function applyDelta<T extends Versioned>(snap: T, msg: StreamMessage): T {
  return { ...snap, ...(msg.delta ?? {}), version: msg.version };
}

/**
 * What to do with a stream message given the local version (pure, unit-tested):
 * apply only version == local + 1, ignore version <= local, anything newer is a gap -> refetch the snapshot.
 */
export function decide(local: number | undefined, incoming: number): 'apply' | 'ignore' | 'resync' {
  if (local === undefined) return 'resync';
  if (incoming <= local) return 'ignore';
  return incoming === local + 1 ? 'apply' : 'resync';
}

/** Full-jitter exponential backoff: random(0, min(cap, base * 2^attempt)). */
export function backoffMs(attempt: number, base = 500, cap = 30_000): number {
  return Math.random() * Math.min(cap, base * 2 ** attempt);
}

/**
 * Snapshot + SSE (realtime-recovery.md §4). On connect: load the owner's snapshot, then apply only newer versions.
 * A gap, a resync, or a heartbeat that knows a newer version reloads the snapshot. While not live (stream down,
 * not allowed, or reconnecting) it polls the snapshot every 10 s, so correctness never depends on the stream.
 */
export function useLiveSnapshot<T extends Versioned>(load: () => Promise<{ data: T; response: Response }>, streamUrl: string | null, deps: unknown[]) {
  const [state, setState] = React.useState<{ data?: T; response?: Response; problem?: Problem; loading: boolean }>({ loading: true });
  const [status, setStatus] = React.useState<LiveStatus>('reconnecting');
  const loadRef = React.useRef(load);
  loadRef.current = load;
  const versionRef = React.useRef<number | undefined>(undefined);

  const refresh = React.useCallback(async () => {
    try {
      const r = await loadRef.current();
      // Never go backwards: a slow snapshot must not undo a newer delta already applied.
      if (versionRef.current === undefined || r.data.version >= versionRef.current) {
        versionRef.current = r.data.version;
        setState({ data: r.data, response: r.response, loading: false });
      }
    } catch (e) {
      setState((s) => ({ ...s, loading: false, problem: e instanceof ApiError ? e.problem : { status: 0, detail: 'Network error; retrying.' } }));
    }
  }, []);

  /** Apply our own command's result (e.g. after join) without waiting for the stream; versions keep it consistent. */
  const accept = React.useCallback((data: T) => {
    if (versionRef.current === undefined || data.version >= versionRef.current) {
      versionRef.current = data.version;
      setState((s) => ({ ...s, data, loading: false, problem: undefined }));
    }
  }, []);

  React.useEffect(() => {
    let closed = false;
    let es: EventSource | null = null;
    let attempt = 0;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let delayedTimer: ReturnType<typeof setTimeout> | undefined;
    let lastEventAt = Date.now();
    versionRef.current = undefined;
    refresh();

    const poll = setInterval(() => {
      if (!es || es.readyState !== EventSource.OPEN) refresh();
    }, 10_000);
    // Watchdog: heartbeats come every 15 s, so 20 s of silence means the instance died without closing the stream.
    let reconnectNow: (() => void) | undefined;
    const watchdog = setInterval(() => {
      if (es && Date.now() - lastEventAt > 20_000) reconnectNow?.();
    }, 5_000);

    const onMessage = (ev: MessageEvent) => {
      const msg = JSON.parse(ev.data) as StreamMessage;
      if (msg.server_time) recordServerTime(msg.server_time, Date.now(), Date.now());
      const d = decide(versionRef.current, msg.version);
      if (d === 'apply') {
        versionRef.current = msg.version;
        setState((s) => (s.data ? { ...s, data: applyDelta(s.data, msg) } : s));
      } else if (d === 'resync') {
        refresh();
      }
    };

    const connect = () => {
      if (closed || !streamUrl) return;
      es = new EventSource(streamUrl);
      lastEventAt = Date.now();
      const seen = () => { lastEventAt = Date.now(); };
      for (const name of ['ready', 'pool.changed', 'resync', 'heartbeat']) es.addEventListener(name, seen);
      es.addEventListener('ready', () => {
        attempt = 0;
        clearTimeout(delayedTimer);
        setStatus('live');
        refresh(); // snapshot after connecting, then only newer versions
      });
      es.addEventListener('pool.changed', onMessage);
      es.addEventListener('resync', () => refresh());
      es.addEventListener('heartbeat', (ev) => {
        const hb = JSON.parse((ev as MessageEvent).data) as StreamMessage;
        if (hb.server_time) recordServerTime(hb.server_time, Date.now(), Date.now());
        if (versionRef.current !== undefined && hb.version > versionRef.current) refresh(); // missed a delta (e.g. Redis down)
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
    if (streamUrl) connect();
    else setStatus('delayed');

    return () => {
      closed = true;
      es?.close();
      clearInterval(poll);
      clearInterval(watchdog);
      clearTimeout(retryTimer);
      clearTimeout(delayedTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { ...state, status, refresh, accept };
}
