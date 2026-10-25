// W3 orchestrator (P2 scope G). Signs in VUS x PER_VU load-test buyers (email OTP via Mailpit, one simulated client IP each, so
// the per-IP OTP limit applies per buyer as in production), picks one pool, holds a live stream on it as an observer
// buyer, runs k6 (grafana/k6 in Docker on the compose network), then checks that three counts agree exactly:
// the pool row's interested_count, a recount of the memberships table, and the last count the stream delivered;
// and, when no request had an ambiguous outcome, baseline + joins - leaves. Writes reports/p2/w3.json.
//   node tools/load/w3.mjs [vus=1000] [duration=5m] [buyers per vu=4]
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const VUS = Number(process.argv[2] || 1000);
const DURATION = process.argv[3] || '5m';
const PER_VU = Number(process.argv[4] || 4);
const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const K6_IMAGE = 'grafana/k6:2.3.0';
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, '.out'); // git-ignored: holds session ids
const ROOT = join(HERE, '..', '..');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

const ipOf = (i) => `10.${(i >> 16) & 255}.${(i >> 8) & 255}.${i & 255}`;

async function signIn(email, ip) {
  const before = Date.now();
  const h = { 'content-type': 'application/json', 'X-Forwarded-For': ip };
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(`${GW}/api/auth/otp/start`, { method: 'POST', headers: h, body: JSON.stringify({ email }) });
    if (r.status === 202) break;
    if (attempt === 5) throw new Error(`otp start ${email}: ${r.status}`);
    await sleep(1000 * (attempt + 1));
  }
  for (let i = 0; i < 120; i++) {
    const m = (await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json()).messages?.[0];
    const code = m && Date.parse(m.Created) >= before - 2000 ? m.Snippet.match(/\b(\d{6})\b/)?.[1] : null;
    if (code) {
      const v = await fetch(`${GW}/api/auth/otp/verify`, { method: 'POST', headers: h, body: JSON.stringify({ email, code }) });
      if (!v.ok) throw new Error(`verify ${email}: ${v.status}`);
      return (await v.json()).session_id;
    }
    await sleep(500);
  }
  throw new Error(`no OTP mail for ${email}`);
}

async function sessions(n) {
  const file = join(OUT, 'sessions.json');
  const have = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : [];
  const alive = have.length && (await fetch(`${GW}/api/me`, { headers: { 'X-Convoy-Session': have[0].session } })).status === 200;
  const list = alive ? have : [];
  const t = Date.now();
  for (let next = list.length; next < n;) {
    const batch = [];
    for (let k = 0; k < 25 && next < n; k++, next++) {
      const i = next;
      batch.push(signIn(`load-${i}@convoy.test`, ipOf(i + 1)).then((session) => ({ i, session, ip: ipOf(i + 1) })));
    }
    list.push(...(await Promise.all(batch)));
    if (list.length % 200 === 0) console.log(`signed in ${list.length}/${n}`);
  }
  list.sort((a, b) => a.i - b.i);
  writeFileSync(file, JSON.stringify(list));
  if (list.length > have.length || !alive) console.log(`sign-in of ${n} buyers took ${((Date.now() - t) / 1000).toFixed(0)} s`);
  return list;
}

async function api(method, path, session, extra = {}) {
  for (let attempt = 1; ; attempt++) { // 503 = retry with the same Idempotency-Key (safe by contract)
    const r = await fetch(GW + path, { method, headers: { 'X-Convoy-Session': session, 'content-type': 'application/json', ...extra.headers }, body: extra.body });
    if (r.ok) return r.json();
    if (r.status !== 503 || attempt === 5) throw new Error(`${method} ${path} -> ${r.status} ${await r.text()}`);
    await sleep(2000 * attempt);
  }
}

function psql(sql) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'convoy_admin', '-d', 'convoy', '-At', '-c', sql], { cwd: ROOT }).toString().trim();
}

const all = await sessions(VUS * PER_VU + 1);
const observer = all[VUS * PER_VU]; // not a VU buyer: stays a member and holds the stream
// A pool key never used before, so no buyer already has a membership in it (Q-045: no rejoin).
// Pool months are this month + the next six; any catalogue (model, city) with a verified price will do.
const used = new Set(psql('select model_slug || \'|\' || city || \'|\' || purchase_month from pool.pools').split(/\s+/));
const combos = psql('select distinct m.slug || \'|\' || vp.city from catalog.variant_prices vp join catalog.variants v on v.id = vp.variant_id '
  + 'join catalog.models m on m.id = v.model_id order by 1').split(/\s+/);
const months = Array.from({ length: 7 }, (_, k) => new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + k, 1)).toISOString().slice(0, 7));
const key = combos.flatMap((c) => months.map((m) => `${c}|${m}`)).find((k) => !used.has(k));
if (!key) throw new Error('every pool key is used: reset the local database');
const [modelSlug, city, month] = key.split('|');
const pool = (await api('POST', '/api/pools', observer.session, { headers: { 'Idempotency-Key': randomUUID() }, body: JSON.stringify({ model_slug: modelSlug, city, purchase_month: month }) })).pool.id;
await fetch(`${GW}/api/pools/${pool}/members`, { method: 'POST', headers: { 'X-Convoy-Session': observer.session, 'Idempotency-Key': randomUUID() } });
const baseline = Number(psql(`select interested_count from pool.pools where id = '${pool}'`));
console.log(`pool ${pool}, baseline interested ${baseline}`);

// Observer stream: the last absolute count delivered, and commit -> client latency of every delivered event.
const stream = { last: null, version: 0, events: 0, resyncs: 0, latencies: [] };
const ctl = new AbortController();
(async () => {
  const res = await fetch(`${GW}/api/stream/pools/${pool}`, { headers: { 'X-Convoy-Session': observer.session }, signal: ctl.signal });
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let k;
      while ((k = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, k);
        buf = buf.slice(k + 2);
        const ev = block.match(/^event: ?(.+)$/m)?.[1];
        const data = block.match(/^data: ?(.+)$/m)?.[1];
        if (!data) continue;
        const m = JSON.parse(data);
        if (ev === 'pool.changed') {
          stream.events++;
          if (m.occurred_at) stream.latencies.push(Date.now() - Date.parse(m.occurred_at));
          if (m.version > stream.version && m.delta?.interested_count !== undefined) {
            stream.version = m.version;
            stream.last = m.delta.interested_count;
          }
        } else if (ev === 'resync') stream.resyncs++;
      }
    }
  } catch { /* aborted */ }
})();

const t0 = Date.now();
// Async spawn: a blocking spawnSync would freeze this process's stream reader and fake the delivery latency.
const k6 = await new Promise((resolve) => spawn('docker', ['run', '--rm', '--network', 'convoy_default', '-v', `${HERE}:/work`, '-e', 'GW=http://gateway:8080', '-e', `POOL_ID=${pool}`,
  '-e', `VUS=${VUS}`, '-e', `PER_VU=${PER_VU}`, '-e', `DURATION=${DURATION}`, K6_IMAGE, 'run', '--quiet', '--summary-export=/work/.out/w3-summary.json', '/work/w3.k6.js'],
{ stdio: ['ignore', 'inherit', 'inherit'] }).on('exit', (status) => resolve({ status })));
const ranMs = Date.now() - t0;
// Drain: wait (up to 3 min) until the stream has delivered the pool's final version; the wait is reported.
const finalVersion = Number(psql(`select version from pool.pools where id = '${pool}'`));
const drainStart = Date.now();
while (stream.version < finalVersion && Date.now() - drainStart < 180_000) await sleep(500);
const drainMs = Date.now() - drainStart;
ctl.abort();

const s = JSON.parse(readFileSync(join(OUT, 'w3-summary.json'), 'utf8')).metrics;
const [row, recount] = [Number(psql(`select interested_count from pool.pools where id = '${pool}'`)),
  Number(psql(`select count(*) from pool.memberships where pool_id = '${pool}' and state = 'INTERESTED'`))];
const joins = s.buyers_joined?.count ?? 0, leaves = s.buyers_left?.count ?? 0, amb = s.unexpected_responses?.count ?? 0;
const lat = [...stream.latencies].sort((a, b) => a - b);
const pct = (p) => lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * p))] : null;
const trend = (m) => m && { p50: Math.round(m['p(50)']), p95: Math.round(m['p(95)']), p99: Math.round(m['p(99)']), max: Math.round(m.max) };
const result = {
  measured_at: new Date().toISOString(),
  commit: execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: ROOT }).toString().trim(),
  env: 'local docker compose (lite) on one laptop; k6 ' + K6_IMAGE + ' in Docker on the compose network; gateway rate limits on (join 20/min/user)',
  vus: VUS, buyers: VUS * PER_VU, duration: DURATION, k6_exit: k6.status, wall_ms: ranMs, pool: '<pool-id>',
  requests: { buyers_joined: joins, buyers_left: leaves, double_clicks: s.double_clicks?.count ?? 0, unexpected_responses: amb, http_reqs: s.http_reqs?.count ?? null, http_failed_rate: s.http_req_failed?.value ?? null },
  latency_ms: { join: trend(s.join_ms), leave: trend(s.leave_ms) },
  counts: { pool_row: row, memberships_recount: recount, stream_last_delivered: stream.last, expected_from_requests: baseline + joins - leaves },
  stream: { events: stream.events, resyncs: stream.resyncs, final_version: finalVersion, last_version_seen: stream.version, catch_up_after_load_ms: drainMs, delivery_p95_ms: pct(0.95), delivery_p99_ms: pct(0.99) },
};
// Integrity: the four counts agree. Refused/failed requests are reported separately (a failure that still took effect
// would break the last equality).
result.counts_exact = row === recount && recount === stream.last && recount === baseline + joins - leaves;
writeFileSync(join(ROOT, 'reports', 'p2', 'w3.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ counts: result.counts, exact: result.counts_exact, latency: result.latency_ms, stream: result.stream, requests: result.requests }));
process.exit(result.counts_exact ? 0 : 1);
