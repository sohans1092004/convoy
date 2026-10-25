'use client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import type { Schemas } from '@/lib/api';
import pricing from '../../spec-tools/vectors/pricing.json';

/**
 * Storybook-only mocks for page states: a fetch stub that answers /api calls per story (or never, for loading), a
 * switch for navigator.onLine, and a fake EventSource. The real page components render against them.
 */
export type Reply = { status: number; body?: unknown } | 'hang';
/** Path regex -> replies in call order (the last one repeats). */
export type Routes = Record<string, Reply[]>;

class FakeEventSource extends EventTarget {
  static mode: 'live' | 'down' = 'down';
  readyState = 0;
  onerror: ((e: Event) => void) | null = null;
  static readonly OPEN = 1;
  constructor(public url: string) {
    super();
    setTimeout(() => {
      if (FakeEventSource.mode === 'live') {
        this.readyState = 1;
        this.dispatchEvent(new MessageEvent('ready', { data: JSON.stringify({ version: 0, server_time: new Date().toISOString() }) }));
      } else {
        this.onerror?.(new Event('error'));
      }
    }, 50);
  }
  close() { this.readyState = 2; }
}

export function Mocked({ routes, offline = false, stream = 'down', children }: { routes: Routes; offline?: boolean; stream?: 'live' | 'down'; children: React.ReactNode }) {
  const [client] = React.useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } }));
  const [ready, setReady] = React.useState(false);
  React.useLayoutEffect(() => {
    const calls: Record<string, number> = {};
    const realFetch = window.fetch;
    window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
      const path = new URL(url, location.href).pathname + new URL(url, location.href).search;
      const key = Object.keys(routes).find((k) => new RegExp(k).test(`${method} ${path}`));
      if (!key) return new Response(JSON.stringify({ status: 404, detail: `no mock for ${method} ${path}` }), { status: 404 });
      const list = routes[key];
      const reply = list[Math.min(calls[key] = (calls[key] ?? -1) + 1, list.length - 1)];
      if (reply === 'hang') return new Promise<Response>(() => {});
      const problem = reply.status >= 400;
      return new Response(JSON.stringify(reply.body ?? {}), { status: reply.status, headers: { 'content-type': problem ? 'application/problem+json' : 'application/json' } });
    }) as typeof fetch;
    const onLine = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
    Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => !offline });
    const RealES = window.EventSource;
    FakeEventSource.mode = stream;
    window.EventSource = FakeEventSource as unknown as typeof EventSource;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Storybook-only mock installer: render the story after the fetch mock is in place
    setReady(true);
    return () => {
      window.fetch = realFetch;
      window.EventSource = RealES;
      if (onLine) delete (navigator as unknown as Record<string, unknown>).onLine;
    };
  }, [routes, offline, stream]);
  return ready ? <QueryClientProvider client={client}>{children}</QueryClientProvider> : null;
}

// ---- fixtures (shapes from the generated client; prices from the verified catalogue vectors)

export const P050 = (pricing as { cases: { id: string; expected: Schemas['catalog']['PriceOutput'] }[] }).cases.find((c) => c.id === 'P050')!.expected;

// Rows copied from the seeded catalogue (GET /api/catalog/models, ruleset 2026.10.24-p0, read 2026-10-25): verified data only.
export const MODELS: Schemas['catalog']['ModelList'] = {
  ruleset_version: '2026.10.24-p0', cities: ['Mumbai', 'Pune', 'Delhi', 'Bengaluru'],
  models: [
    { id: '95df624e-9cb0-5225-8d19-8f22a14c0841', slug: 'hyundai-creta', name: 'Creta', brand: 'Hyundai', body_type: 'SUV', variant_count: 5, from_ex_showroom_paise: 109070000, any_city_confirmed: false, evidence: 'CATALOGUE_ESTIMATE' },
    { id: '980eec82-f8d9-545d-a6f3-e56f84915c51', slug: 'mg-hector', name: 'Hector', brand: 'MG Motor', body_type: 'SUV', variant_count: 4, from_ex_showroom_paise: 121400000, any_city_confirmed: false, evidence: 'CATALOGUE_ESTIMATE' },
    { id: '345f5156-6af9-53c9-8561-03ec762ddee3', slug: 'mg-windsor-ev', name: 'Windsor EV', brand: 'MG Motor', body_type: 'crossover', variant_count: 4, from_ex_showroom_paise: 148480000, any_city_confirmed: false, evidence: 'CATALOGUE_ESTIMATE' },
  ],
};

export const VARIANTS: Schemas['catalog']['Variant'][] = [
  { id: '96a9e043-f401-5b03-b82a-52e617913ce5', code: 'swift-lxi-mt', name: 'LXi MT', fuel: 'PETROL', transmission: 'MT', engine_cc: 1197, note: null, prices: [] },
  { id: '709324c3-8db5-5baf-a801-575995a81409', code: 'swift-vxi-amt', name: 'VXi AMT', fuel: 'PETROL', transmission: 'AMT', engine_cc: 1197, note: null, prices: [] },
];

export const ESTIMATE: Schemas['catalog']['Estimate'] = {
  variant: { id: '96a9e043-f401-5b03-b82a-52e617913ce5', code: 'swift-lxi-mt', name: 'LXi MT' }, city: 'Pune', city_confirmed: false,
  // Output: pricing vector P050 (spec-tools/vectors), a verified engine case, not a live Swift price.
  source: { url: 'https://www.marutisuzuki.com/arena/swift/price', accessed_on: '2026-10-24' },
  snapshot: { inputs: {}, ruleset_version: '2026.10.24-p0', output: P050, sha256: 'a'.repeat(64), computed_at: '2026-10-24T06:00:00Z' },
};

const deadline = new Date(Date.now() + 3 * 864e5).toISOString();
export function pool(over: Partial<Schemas['pool']['Pool']> = {}): Schemas['pool']['Pool'] {
  return {
    id: 'p1', model_slug: 'maruti-swift', brand: 'Maruti Suzuki', city: 'Pune', state_code: 'MH', accepted_variant_ids: ['96a9e043-f401-5b03-b82a-52e617913ce5'], min_members: 5, state: 'FORMING',
    end_reason: null, round_set: 1, purchase_month: '2026-11', forming_deadline: deadline, decision_deadline: null, confirmation_deadline: null,
    purchase_window_end: deadline, interested_count: 4, committed_count: 2, version: 7, server_time: new Date().toISOString(), rounds: [],
    deposit_amount_paise: 99900, terms_version: '2026-10-24-p0', ...over,
  };
}

export const MEMBER: Schemas['pool']['Membership'] = {
  id: 'mem1', pool_id: 'p1', state: 'INTERESTED', current_payment_id: null, terms_version: null, accepted_quote_id: null, accepted_variant_id: null,
  release_reason: null, version: 1,
};

export const problem = (status: number, code: string, detail: string) => ({ status, body: { status, code, detail, title: detail } });
