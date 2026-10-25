// W3 (P2 scope G): concurrent joins and leaves on ONE pool. A buyer cannot rejoin after leaving (Q-045), so each VU
// works through its own buyers: join (30 % as a double-click: the same Idempotency-Key sent twice at once), wait,
// leave for half of them, next buyer. All VUs start within 10 s (contention burst), then churn until DURATION.
// Run by tools/load/w3.mjs, which signs the buyers in and checks the counts afterwards.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const sessions = JSON.parse(open('/work/.out/sessions.json'));
const GW = __ENV.GW;
const POOL = __ENV.POOL_ID;
const VUS = Number(__ENV.VUS);
const PER_VU = Number(__ENV.PER_VU);
const joined = new Counter('buyers_joined');
const left = new Counter('buyers_left');
const doubleClicks = new Counter('double_clicks');
const unexpected = new Counter('unexpected_responses');
const joinMs = new Trend('join_ms', true);
const leaveMs = new Trend('leave_ms', true);

export const options = {
  scenarios: { w3: { executor: 'per-vu-iterations', vus: VUS, iterations: PER_VU, maxDuration: __ENV.DURATION, gracefulStop: '30s' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const headers = (s, extra) => ({ 'X-Convoy-Session': s.session, 'X-Forwarded-For': s.ip, 'Content-Type': 'application/json', ...extra });

export default function () {
  if (__ITER === 0) sleep(Math.random() * 10); // load-workloads.md W3: all VUs start within 10 s
  const s = sessions[__ITER * VUS + (__VU - 1)];
  const key = crypto.randomUUID();
  const join = { method: 'POST', url: `${GW}/api/pools/${POOL}/members`, params: { headers: headers(s, { 'Idempotency-Key': key }), tags: { name: 'join' } } };
  const double = Math.random() < 0.3;
  const res = double ? http.batch([join, join]) : [http.request(join.method, join.url, null, join.params)];
  if (double) doubleClicks.add(1);
  res.forEach((r) => joinMs.add(r.timings.duration));
  const ok = res.filter((r) => r.status === 201);
  if (ok.length === 0) { unexpected.add(1); return; }
  if (double && ok.length === 2 && ok[0].json('id') !== ok[1].json('id')) unexpected.add(1); // two memberships = a bug
  joined.add(1);
  sleep(2 + Math.random() * 20);
  if (Math.random() < 0.5) {
    const r = http.del(`${GW}/api/pools/${POOL}/members/me`, null, {
      headers: headers(s, { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${ok[0].json('version')}"` }), tags: { name: 'leave' },
    });
    leaveMs.add(r.timings.duration);
    if (r.status === 200) left.add(1);
    else unexpected.add(1);
  }
  sleep(Math.random() * 10);
}
