// W9 mixed peak (load-workloads.md): 40 % catalogue (60 model list / 30 model detail / 10 price estimate), 30 % pool
// reads, 10 % joins, 10 % comparison reads, 5 % quote submits, 5 % order reads. Open model (constant arrival rate per
// route group), so a slow system gets more concurrent requests, not fewer. Stages: warm-up to RATE, hold, then step up
// by RATE/2 every STEP until the abort thresholds trip (error rate > 1 % or p95 doubled vs the steady phase).
// Fixture from tools/load/w9-setup.mjs: { buyers: [session], dealers: [{session, dealership_id}], models: [slug],
// variants: [id], cities: [..], pools: [id] (OPEN), quotePools: [id] (QUOTE_OPEN), variantOf: {poolId: variantId} }.
//   k6 run -e BASE=https://<alb> -e RATE=1000 -e HOLD=15m tools/load/w9.k6.js   (from the load generator in the run's VPC)
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const fx = JSON.parse(open(__ENV.FIXTURE || '/work/w9-fixture.json'));
const BASE = __ENV.BASE;
const RATE = Number(__ENV.RATE || 1000);
const HOLD = __ENV.HOLD || '15m';
const STEP = __ENV.STEP || '3m';
const STEPS = Number(__ENV.STEPS || 6);
const unexpected = new Counter('unexpected_status');

const share = { catalog: 0.40, pool_read: 0.30, join: 0.10, compare: 0.10, quote_submit: 0.05, order_read: 0.05 };
function stages(r) {
  const s = [{ target: r, duration: '5m' }, { target: r, duration: HOLD }];
  for (let i = 1; i <= STEPS; i++) s.push({ target: r * (1 + i / 2), duration: '30s' }, { target: r * (1 + i / 2), duration: STEP });
  return s;
}
const scenarios = {};
for (const [name, f] of Object.entries(share)) {
  scenarios[name] = { executor: 'ramping-arrival-rate', exec: name, startRate: 1, timeUnit: '1s', preAllocatedVUs: Math.ceil(RATE * f),
    maxVUs: Math.ceil(RATE * f * 20), stages: stages(RATE * f) };
}
// named routes for the report (PLAN §3.3 targets: p95 < 300 ms, p99 < 800 ms)
const routes = ['model_list', 'model_detail', 'price_estimate', 'pool_read', 'join', 'compare', 'quote_submit', 'order_read'];
export const options = {
  insecureSkipTLSVerify: true, // the full profile's self-signed ALB certificate (this profile only; docs/runbooks/full-profile.md)
  discardResponseBodies: true,
  scenarios,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
  thresholds: Object.assign(
    { http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '1m' }] },
    ...routes.map((r) => ({ [`http_req_duration{route:${r}}`]: ['p(95)<300', 'p(99)<800'] }))),
};

const pick = (a) => a[Math.floor(Math.random() * a.length)];
// Through the ALB the web BFF is the entry point, so requests look like the browser's: session cookie, a double-submit
// CSRF pair (any value, cookie == header) and a same-origin Origin on writes (web/app/api/[...path]/route.ts).
const h = (s, extra) => ({ headers: { Cookie: `cv_sid=${s}; cv_csrf=load`, 'X-CSRF-Token': 'load', Origin: BASE, 'Content-Type': 'application/json', ...extra } });
const tag = (route, p) => ({ ...p, tags: { route } });
function check(r, ok, route) { if (!ok.includes(r.status)) unexpected.add(1, { route, status: String(r.status) }); }

export function catalog() {
  const s = pick(fx.buyers), x = Math.random();
  if (x < 0.6) check(http.get(`${BASE}/api/catalog/models?city=${pick(fx.cities)}`, tag('model_list', h(s))), [200], 'model_list');
  else if (x < 0.9) check(http.get(`${BASE}/api/catalog/models/${pick(fx.models)}`, tag('model_detail', h(s))), [200], 'model_detail');
  else check(http.get(`${BASE}/api/catalog/variants/${pick(fx.variants)}/price-estimate?city=${pick(fx.cities)}`, tag('price_estimate', h(s))), [200], 'price_estimate');
}
export function pool_read() { check(http.get(`${BASE}/api/pools/${pick(fx.pools)}`, tag('pool_read', h(pick(fx.buyers)))), [200], 'pool_read'); }
export function join() {
  // a buyer joins a pool once; a repeat for the same pair is a legitimate 409 (already a member), not an error
  check(http.post(`${BASE}/api/pools/${pick(fx.pools)}/members`, null, tag('join', h(pick(fx.buyers), { 'Idempotency-Key': crypto.randomUUID() }))), [201, 409], 'join');
}
export function compare() { check(http.get(`${BASE}/api/pools/${pick(fx.quotePools)}/quotes`, tag('compare', h(pick(fx.buyers)))), [200, 403, 409], 'compare'); }
export function quote_submit() {
  // dealer opens (or reuses) its draft on a QUOTE_OPEN pool; the submit itself happens once per dealer and pool (W4 covers the burst)
  const d = pick(fx.dealers), p = pick(fx.quotePools);
  check(http.post(`${BASE}/api/dealer/pools/${p}/quotes`, null, tag('quote_submit', h(d.session, { 'Idempotency-Key': crypto.randomUUID() }))), [200, 201, 409], 'quote_submit');
}
export function order_read() { check(http.get(`${BASE}/api/me/orders`, tag('order_read', h(pick(fx.buyers)))), [200], 'order_read'); }
