// W3 (P2 scope G, AMENDMENT-008): concurrent joins, leaves and rejoins on ONE pool. Each VU works through its own buyers;
// a buyer joins (30 % as a double-click: the same Idempotency-Key sent twice at once), then up to twice leaves and tries
// to rejoin: the rejoin after the 1st leave succeeds, the one after the 2nd must be refused (409 rejoin-limit-reached).
// All VUs start within 10 s (contention burst), then churn until DURATION.
// Run by tools/load/w3.mjs, which signs the buyers in and checks the counts afterwards.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const sessions = JSON.parse(open('/work/.out/sessions.json'));
const GW = __ENV.GW;
const POOL = __ENV.POOL_ID;
const VUS = Number(__ENV.VUS);
const PER_VU = Number(__ENV.PER_VU);
const joined = new Counter('joins_ok');
const left = new Counter('leaves_ok');
const refused = new Counter('rejoins_refused');
const doubleClicks = new Counter('double_clicks');
const unexpected = new Counter('unexpected_responses');
const joinMs = new Trend('join_ms', true);
const leaveMs = new Trend('leave_ms', true);

export const options = {
  scenarios: { w3: { executor: 'per-vu-iterations', vus: VUS, iterations: PER_VU, maxDuration: __ENV.DURATION, gracefulStop: '30s' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const headers = (s, extra) => ({ 'X-Convoy-Session': s.session, 'X-Forwarded-For': s.ip, 'Content-Type': 'application/json', ...extra });

function join(s, double) {
  const req = { method: 'POST', url: `${GW}/api/pools/${POOL}/members`, params: { headers: headers(s, { 'Idempotency-Key': crypto.randomUUID() }), tags: { name: 'join' } } };
  const res = double ? http.batch([req, req]) : [http.request(req.method, req.url, null, req.params)];
  if (double) doubleClicks.add(1);
  res.forEach((r) => joinMs.add(r.timings.duration));
  const ok = res.filter((r) => r.status === 201);
  if (double && ok.length === 2 && ok[0].json('id') !== ok[1].json('id')) unexpected.add(1); // two memberships = a bug
  if (ok.length) { joined.add(1); return ok[0].json('version'); }
  if (res.some((r) => r.status === 409 && r.json('code') === 'REJOIN_LIMIT_REACHED')) refused.add(1);
  else unexpected.add(1);
  return null;
}

function leave(s, version) {
  const r = http.del(`${GW}/api/pools/${POOL}/members/me`, null, {
    headers: headers(s, { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` }), tags: { name: 'leave' },
  });
  leaveMs.add(r.timings.duration);
  if (r.status === 200) { left.add(1); return true; }
  unexpected.add(1);
  return false;
}

export default function () {
  if (__ITER === 0) sleep(Math.random() * 10); // load-workloads.md W3: all VUs start within 10 s
  const s = sessions[__ITER * VUS + (__VU - 1)];
  let version = join(s, Math.random() < 0.3);
  for (let leaves = 0; version !== null && leaves < 2 && Math.random() < 0.6; leaves++) {
    sleep(2 + Math.random() * 10);
    if (!leave(s, version)) return;
    sleep(1 + Math.random() * 5);
    version = join(s, false); // after the 1st leave: a rejoin; after the 2nd: refused (null)
  }
  if (version === null) return;
  sleep(Math.random() * 10);
}
