'use client';
import * as React from 'react';

/**
 * E122 buyer stream (P3.E): how many dealers have quoted so far, live, and a one-shot signal when the final round closes
 * and the full comparison can be loaded. The stream never carries quote contents. Null count = not known yet.
 */
export function useQuoteCount(poolId: string, enabled: boolean, onComparisonReady: () => void) {
  const [count, setCount] = React.useState<number | null>(null);
  const ready = React.useRef(onComparisonReady);
  ready.current = onComparisonReady;
  React.useEffect(() => {
    if (!enabled) return;
    const es = new EventSource(`/api/stream/me?pool_id=${encodeURIComponent(poolId)}`);
    es.addEventListener('ready', (ev) => setCount(JSON.parse((ev as MessageEvent).data).quote_count ?? null));
    es.addEventListener('quotes.changed', (ev) => {
      const d = JSON.parse((ev as MessageEvent).data).delta ?? {};
      if (typeof d.quote_count === 'number') setCount(d.quote_count);
      if (d.comparison_ready) ready.current();
    });
    return () => es.close(); // EventSource reconnects by itself; the pool snapshot stays the truth
  }, [poolId, enabled]);
  return count;
}
