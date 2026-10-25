// W5 local (P2 scope G): many live pool streams through the gateway to two realtime instances. Measures fan-out latency
// (event commit -> client), a reconnect storm (one realtime instance killed: time until every cut client is back and
// holds a fresh snapshot), slow consumers (2 % never read: are they cut, and do the others stay fast) and memory per
// 1,000 connections. Local and honest: one laptop, not the 10,000-connection target. Writes reports/p2/w5.json.
//   node tools/load/w5.mjs [connections=2000] [hold seconds=300]   (run tools/load/w3.mjs once first: it signs buyers in)
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const N = Number(process.argv[2] || 2000);
const HOLD_S = Number(process.argv[3] || 300);
const POOLS = 20;
const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT }).toString().trim();
const psql = (sql) => sh('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'convoy_admin', '-d', 'convoy', '-At', '-c', sql]);
const mem = () => Object.fromEntries(sh('docker', ['stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', 'convoy-realtime-1-1', 'convoy-realtime-2-1', 'convoy-gateway-1'])
  .split('\n').map((l) => { const [name, use] = l.split(' '); const v = parseFloat(use); return [name.replace(/^convoy-|-1$/g, ''), use.includes('GiB') ? v * 1024 : v]; }));
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s.length ? Math.round(s[Math.min(s.length - 1, Math.floor(s.length * p))]) : null; };

async function call(method, path, s, body) {
  for (let attempt = 1; ; attempt++) {
    const r = await fetch(GW + path, { method, headers: { 'X-Convoy-Session': s.session, 'X-Forwarded-For': s.ip, 'content-type': 'application/json', 'Idempotency-Key': randomUUID() }, body });
    if (r.ok || r.status === 409) return r;
    if (attempt === 4) throw new Error(`${method} ${path} -> ${r.status}`);
    await sleep(1000 * attempt);
  }
}

const S = JSON.parse(readFileSync(join(HERE, '.out', 'sessions.json'), 'utf8'));
if (S.length < N + 600) throw new Error(`need ${N + 600} signed-in buyers, have ${S.length}: run tools/load/w3.mjs first`);
const watchers = S.slice(0, N), spare = S.slice(N);

// Pools on unused keys (a buyer who left cannot rejoin, Q-045); watcher i joins pool i % POOLS.
const used = new Set(psql("select model_slug || '|' || city || '|' || purchase_month from pool.pools").split(/\s+/));
const combos = psql("select distinct m.slug || '|' || vp.city from catalog.variant_prices vp join catalog.variants v on v.id = vp.variant_id join catalog.models m on m.id = v.model_id order by 1").split(/\s+/);
const months = Array.from({ length: 7 }, (_, k) => new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + k, 1)).toISOString().slice(0, 7));
const keys = combos.flatMap((c) => months.map((m) => `${c}|${m}`)).filter((k) => !used.has(k)).slice(0, POOLS);
if (keys.length < POOLS) throw new Error('not enough unused pool keys: reset the local database');
const pools = [];
for (const k of keys) {
  const [model_slug, city, purchase_month] = k.split('|');
  pools.push((await (await call('POST', '/api/pools', spare[0], JSON.stringify({ model_slug, city, purchase_month }))).json()).pool.id);
}
console.log(`${POOLS} pools; joining ${N} watchers`);
for (let i = 0; i < N; i += 50) await Promise.all(watchers.slice(i, i + 50).map((s, k) => call('POST', `/api/pools/${pools[(i + k) % POOLS]}/members`, s)));

const memBefore = mem();
const clients = [];
const lat = [], latSlowPhase = [];
let measuring = false;
let storm = null; // { at, cut: Set<client> }

function open(c) {
  c.ctl = new AbortController();
  const started = Date.now();
  fetch(`${GW}/api/stream/pools/${c.pool}`, { headers: { 'X-Convoy-Session': c.s.session, 'X-Forwarded-For': c.s.ip }, signal: c.ctl.signal }).then(async (res) => {
    if (!res.ok) throw new Error(`stream ${res.status}`);
    c.connected = true;
    c.connects++;
    if (storm && c.droppedAt >= storm.at && !c.recoveredAt) {
      const snap = await fetch(`${GW}/api/pools/${c.pool}`, { headers: { 'X-Convoy-Session': c.s.session, 'X-Forwarded-For': c.s.ip } }); // snapshot after reconnecting
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
  const c = { s: watchers[i], pool: pools[i % POOLS], slow: i % 50 === 7, connected: false, connects: 0 };
  clients.push(c);
  open(c);
  if (i % 100 === 99) await sleep(1000); // ramp ~100 connections/s
}
// Before measuring, let realtime finish the backlog of the watchers' own join events (else it counts as latency).
const ids = pools.map((p) => `'${p}'`).join(',');
for (let t = Date.now(); Date.now() - t < 300_000; await sleep(2000)) {
  const [out, inb] = psql(`select (select count(*) from pool.outbox where aggregate_id in (${ids})), (select count(*) from realtime.inbox i join pool.outbox o on o.event_id = i.event_id where o.aggregate_id in (${ids}))`).split('|').map(Number);
  if (inb >= out) break;
}
await sleep(5000);
measuring = true;
const openNow = clients.filter((c) => c.connected).length;
const memLoaded = mem();
console.log(`${openNow} streams open; hold ${HOLD_S}s with events; storm at half-time`);

// Events: spare buyers join, 60 % into pool 0 (hot, where some slow consumers sit), the rest spread, ~5 joins/s.
let spareIdx = 1, events = 0;
const genStop = Date.now() + HOLD_S * 1000;
const gen = (async () => {
  while (Date.now() < genStop && spareIdx < spare.length) {
    const p = Math.random() < 0.6 ? pools[0] : pools[1 + Math.floor(Math.random() * (POOLS - 1))];
    call('POST', `/api/pools/${p}/members`, spare[spareIdx++]).then(() => events++, () => {});
    await sleep(200);
  }
})();

await sleep((HOLD_S / 2) * 1000);
const instance = 'realtime-1';
const before = clients.filter((c) => c.connected).length;
sh('docker', ['compose', 'kill', instance]);
storm = { at: Date.now(), cut: new Set() };
await gen;
await sleep(5000);
clients.filter((c) => !c.slow && c.droppedAt >= storm.at).forEach((c) => storm.cut.add(c));
console.log(`killed ${instance}: ${storm.cut.size} of ${before} streams cut`);
const recovered = [...storm.cut].filter((c) => c.recoveredAt);
sh('docker', ['compose', 'start', instance]);
clients.forEach((c) => { c.stopped = true; c.ctl.abort(); });

const slow = clients.filter((c) => c.slow);
const result = {
  measured_at: new Date().toISOString(),
  commit: sh('git', ['rev-parse', '--short', 'HEAD']),
  env: 'local docker compose (lite) on one laptop; Node fetch clients on the host through the gateway; realtime x2 behind the gateway load balancer; heartbeat 15 s; queue 256 per connection',
  target_note: 'local run, not the 10,000-connection W5 target (load-workloads.md); numbers do not extrapolate to production',
  connections: { requested: N, open_after_ramp: openNow, pools: POOLS, slow_consumers: slow.length },
  events_generated: events, hold_s: HOLD_S,
  delivery_ms: { p50: pct(lat, 0.5), p95: pct(lat, 0.95), p99: pct(lat, 0.99), samples: lat.length },
  delivery_after_storm_ms: { p95: pct(latSlowPhase, 0.95), samples: latSlowPhase.length },
  storm: { killed: instance, streams_cut: storm.cut.size, recovered_with_snapshot: recovered.length,
    recovery_p95_ms: pct(recovered.map((c) => c.recoveredAt - storm.at), 0.95), recovery_max_ms: pct(recovered.map((c) => c.recoveredAt - storm.at), 1) },
  slow_consumers: { cut_by_server: slow.filter((c) => c.cutAt).length, of: slow.length },
  memory_mib: { before: memBefore, with_connections: memLoaded,
    realtime_per_1000_connections: Math.round(((memLoaded['realtime-1'] + memLoaded['realtime-2']) - (memBefore['realtime-1'] + memBefore['realtime-2'])) / openNow * 1000),
    gateway_per_1000_connections: Math.round((memLoaded.gateway - memBefore.gateway) / openNow * 1000) },
};
writeFileSync(join(ROOT, 'reports', 'p2', 'w5.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
process.exit(0);
