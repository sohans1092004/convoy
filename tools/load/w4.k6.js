// W4 deadline burst (load-workloads.md): every (pool, dealer) pair prepares a draft, then submits at a planned offset from
// that pool's own sealed-round deadline T: 70 % in [T-5 s, T+1 s), 100 pairs deliberately at T+0..+2 s (must be refused
// with 409), the rest spread over the 120 s before; 30 % revise once (a second submit). Records, per submit, the client
// send time vs T and the response; the database check (0 committed at or after T, 0 lost) is scripts/p7-w4-check.sh.
// Fixture: tools/load/w4_setup.py.   k6 run -e BASE=https://<alb> w4.k6.js
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const fx = JSON.parse(open(__ENV.FIXTURE || '/work/w4-fixture.json'));
const BASE = __ENV.BASE;
const pairs = fx.pairs;
const ok = new Counter('submit_2xx'), late = new Counter('submit_409_deadline'), other = new Counter('submit_other');
const finalSubmit = new Trend('submit_final5s_ms', true);
// Offsets from T in ms (deterministic per pair index, so a re-run plans the same burst)
const LATE = Math.min(100, Math.floor(pairs.length * 0.1));
function offset(i) {
  if (i < LATE) return (i % 20) * 100; // T+0..+1.9 s: deliberately late
  const r = ((i * 7919) % 1000) / 1000;
  return r < 0.7 ? -5000 + Math.floor(r / 0.7 * 6000) : -120000 + Math.floor((r - 0.7) / 0.3 * 114000);
}

export const options = {
  insecureSkipTLSVerify: true, // the full profile's self-signed ALB certificate (this profile only)
  scenarios: { w4: { executor: 'per-vu-iterations', vus: pairs.length, iterations: 1, maxDuration: '20m' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};
const h = (s, extra) => ({ headers: { Cookie: `cv_sid=${s}; cv_csrf=load`, 'X-CSRF-Token': 'load', Origin: BASE, 'Content-Type': 'application/json', ...extra } });

export default function () {
  const i = exec.vu.idInTest - 1, p = pairs[i];
  const T = Date.parse(p.deadline);
  const d = http.post(`${BASE}/api/dealer/pools/${p.pool}/quotes`, null, h(p.dealer, { 'Idempotency-Key': `w4-draft-${i}` }));
  if (d.status !== 201 && d.status !== 200) { other.add(1, { step: 'draft', status: String(d.status) }); return; }
  const draft = d.json();
  const body = JSON.stringify({ variants: [{ variant_id: p.variant, ex_showroom_paise: p.price, registration_paise: null, insurance: null, accessories: [], fees: [], other_charges: [],
    stock: { units: 5, colours: ['White'], delivery_date: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10) } }],
    tiers: [{ min: 1, max: null, discount_paise: 1000000 }], min_fulfilment: 1, max_units: 5, valid_until: new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10) });
  const saved = http.put(`${BASE}/api/dealer/quotes/${draft.id}/draft`, body, h(p.dealer, { 'If-Match': `"${draft.version}"` }));
  if (saved.status !== 200) { other.add(1, { step: 'save', status: String(saved.status) }); return; }
  let version = saved.json('version');
  const submits = (i >= LATE && i % 10 < 3) ? 2 : 1; // ~30 % revise once
  const at = T + offset(i);
  for (let k = 0; k < submits; k++) {
    const wait = (k === 0 ? at - 1500 : at) - Date.now(); // a revision's first submit goes 1.5 s earlier
    if (wait > 0) sleep(wait / 1000);
    const sent = Date.now();
    const r = http.post(`${BASE}/api/dealer/quotes/${draft.id}/submit`, null, h(p.dealer, { 'Idempotency-Key': `w4-submit-${i}-${k}`, 'If-Match': `"${version}"` }));
    if (sent >= T - 5000 && sent < T) finalSubmit.add(r.timings.duration);
    if (r.status === 200 || r.status === 201) { ok.add(1); version = r.json('version') ?? version; }
    else if (r.status === 409) late.add(1, { sent_after_T: String(sent >= T) });
    else other.add(1, { step: 'submit', status: String(r.status) });
    console.log(JSON.stringify({ w4: i, quote: draft.id, pool: p.pool, k, sent_minus_T_ms: sent - T, status: r.status })); // for the DB check
  }
}
