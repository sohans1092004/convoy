// W5 on the full profile (P7.B), from the load generator in the run's VPC: N live pool streams through the ALB and the web
// BFF (browser-style session cookie), fan-out latency (event commit -> client), a reconnect storm (one realtime task
// stopped through the ECS API: time until every cut client is back and holds a fresh snapshot), slow consumers (2 % that
// read one chunk per 10 s must be cut by the server). Memory per 1,000 connections comes afterwards from CloudWatch
// (ECS MemoryUtilization between t0 and t_loaded, scripts/p7-load.sh). Derived from tools/load/w5.mjs (local).
//   node w5_aws.mjs <base url> <sessions.json> <cluster> [connections=10000] [hold seconds=1800] [pools=500]
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [GW, SESSIONS, CLUSTER] = process.argv.slice(2, 5);
const N = Number(process.argv[5] || 10000), HOLD_S = Number(process.argv[6] || 1800), POOLS = Number(process.argv[7] || 500);
// self-signed ALB certificate (full profile only): run with NODE_TLS_REJECT_UNAUTHORIZED=0 (this process only)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (cmd, args) => execFileSync(cmd, args).toString().trim();
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s.length ? Math.round(s[Math.min(s.length - 1, Math.floor(s.length * p))]) : null; };
const hdr = (s) => ({ Cookie: `cv_sid=${s.session}; cv_csrf=load`, 'X-CSRF-Token': 'load', Origin: GW, 'content-type': 'application/json' });
async function call(method, path, s, body) {
  for (let attempt = 1; ; attempt++) {
    const r = await fetch(GW + path, { method, headers: { ...hdr(s), 'Idempotency-Key': randomUUID() }, body });
    if (r.ok || r.status === 409) return r;
    if (attempt === 4) throw new Error(`${method} ${path} -> ${r.status}`);
    await sleep(1000 * attempt);
  }
}

const S = JSON.parse(readFileSync(SESSIONS, 'utf8')).filter((x) => x.role === 'buyer');
if (S.length < N + 600) throw new Error(`need ${N + 600} provisioned buyers (LOAD_USERS), have ${S.length}`);
const watchers = S.slice(0, N), spare = S.slice(N);
const models = (await (await call('GET', '/api/catalog/models?city=Pune', spare[0])).json()).models.map((m) => m.slug);
const cities = ['Pune', 'Mumbai', 'Bengaluru', 'Delhi', 'Hyderabad', 'Chennai'];
const month = new Date(Date.now() + 75 * 864e5).toISOString().slice(0, 7);
const pools = [];
for (const m of models) for (const c of cities) {
  if (pools.length >= POOLS) break;
  pools.push((await (await call('POST', '/api/pools', spare[0], JSON.stringify({ model_slug: m, city: c, purchase_month: month }))).json()).pool.id);
}
console.log(`${pools.length} pools; joining ${N} watchers`);
for (let i = 0; i < N; i += 50) await Promise.all(watchers.slice(i, i + 50).map((s, k) => call('POST', `/api/pools/${pools[(i + k) % pools.length]}/members`, s)));

const t0 = new Date().toISOString();
const clients = [];
const lat = [], latSlowPhase = [];
let measuring = false;
let storm = null; // { at, cut: Set<client> }

function open(c) {
  c.ctl = new AbortController();
  const started = Date.now();
  fetch(`${GW}/api/stream/pools/${c.pool}`, { headers: hdr(c.s), signal: c.ctl.signal }).then(async (res) => {
    if (!res.ok) throw new Error(`stream ${res.status}`);
    c.connected = true;
    c.connects++;
    if (storm && c.droppedAt >= storm.at && !c.recoveredAt) {
      const snap = await fetch(`${GW}/api/pools/${c.pool}`, { headers: hdr(c.s) }); // snapshot after reconnecting
      if (snap.ok) c.recoveredAt = Date.now();
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      if (c.slow) await sleep(10_000); // reads one chunk per 10 s: the server's bounded queue must cut it, not buffer forever
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let k;
      while ((k = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, k);
        buf = buf.slice(k + 2);
        const ev = block.match(/^event: ?(.+)$/m)?.[1];
        const data = block.match(/^data: ?(.+)$/m)?.[1];
        if (ev === 'pool.changed' && data) {
          const at = Date.parse(JSON.parse(data).occurred_at);
          if (at && measuring) (storm ? latSlowPhase : lat).push(Date.now() - at);
        }
        if (ev === 'overloaded') c.overloaded = true;
      }
    }
    throw new Error('closed');
  }).catch(() => {
    c.connected = false;
    c.droppedAt = Date.now();
    if (c.stopped) return;
    if (c.slow) { c.cutAt ??= Date.now(); return; }
    setTimeout(() => open(c), 500 + Math.random() * Math.min(10_000, 500 * 2 ** Math.min(c.connects, 5))); // full-jitter backoff
  });
  return started;
}

console.log(`opening ${N} streams`);
for (let i = 0; i < N; i++) {
  const c = { s: watchers[i], pool: pools[i % pools.length], slow: i % 50 === 7, connected: false, connects: 0 };
  clients.push(c);
  open(c);
  if (i % 100 === 99) await sleep(1000); // ramp ~100 connections/s
}
// No database access from the generator: let the watchers' own join events drain before measuring.
await sleep(90_000);
measuring = true;
const openNow = clients.filter((c) => c.connected).length;
const tLoaded = new Date().toISOString();
console.log(`${openNow} streams open; hold ${HOLD_S}s with events; storm at half-time`);

// Events: spare buyers join, 60 % into pool 0 (hot, where some slow consumers sit), the rest spread, ~5 joins/s.
let spareIdx = 1, events = 0;
const genStop = Date.now() + HOLD_S * 1000;
const gen = (async () => {
  while (Date.now() < genStop && spareIdx < spare.length) {
    const p = Math.random() < 0.6 ? pools[0] : pools[1 + Math.floor(Math.random() * (pools.length - 1))];
    call('POST', `/api/pools/${p}/members`, spare[spareIdx++]).then(() => events++, () => {});
    await sleep(200);
  }
})();

await sleep((HOLD_S / 2) * 1000);
const before = clients.filter((c) => c.connected).length;
const instance = sh('aws', ['ecs', 'list-tasks', '--cluster', CLUSTER, '--service-name', 'realtime', '--query', 'taskArns[0]', '--output', 'text']);
sh('aws', ['ecs', 'stop-task', '--cluster', CLUSTER, '--task', instance, '--reason', 'P7 W5 reconnect storm']); // ECS starts a replacement
storm = { at: Date.now(), cut: new Set() };
await gen;
await sleep(5000);
clients.filter((c) => !c.slow && c.droppedAt >= storm.at).forEach((c) => storm.cut.add(c));
console.log(`killed ${instance}: ${storm.cut.size} of ${before} streams cut`);
const recovered = [...storm.cut].filter((c) => c.recoveredAt);
clients.forEach((c) => { c.stopped = true; c.ctl.abort(); });

const slow = clients.filter((c) => c.slow);
const result = {
  measured_at: new Date().toISOString(), env: 'AWS full profile; Node fetch clients on the load generator (same VPC) through the ALB and the web BFF',
  connections: { requested: N, open_after_ramp: openNow, pools: pools.length, slow_consumers: slow.length },
  events_generated: events, hold_s: HOLD_S, t0, t_loaded: tLoaded,
  delivery_ms: { p50: pct(lat, 0.5), p95: pct(lat, 0.95), p99: pct(lat, 0.99), samples: lat.length },
  delivery_after_storm_ms: { p95: pct(latSlowPhase, 0.95), samples: latSlowPhase.length },
  storm: { stopped_task: instance, streams_cut: storm.cut.size, recovered_with_snapshot: recovered.length,
    recovery_p95_ms: pct(recovered.map((c) => c.recoveredAt - storm.at), 0.95), recovery_max_ms: pct(recovered.map((c) => c.recoveredAt - storm.at), 1) },
  slow_consumers: { cut_by_server: slow.filter((c) => c.cutAt).length, of: slow.length },
};
writeFileSync('w5.json', JSON.stringify(result, null, 2) + String.fromCharCode(10));
console.log(JSON.stringify(result));
process.exit(0);
