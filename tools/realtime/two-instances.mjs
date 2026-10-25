// Realtime across two instances, against the docker compose stack (P2 exit criterion 5).
//   node tools/realtime/two-instances.mjs
// 1. N SSE clients for one pool land on both realtime instances (gateway lb://realtime).
// 2. A join reaches every client exactly once, version-stamped, with no buyer identity.
// 3. Redis FLUSHALL changes nothing: the next join still reaches every client.
// 4. Redis stopped: only the consuming instance's clients get the delta; the others learn the newer version from
//    the next heartbeat (15 s) and resync from the snapshot. Redis back: fan-out resumes.
// 5. One realtime instance killed: its clients reconnect to the other; recovery time is measured.
// Exits non-zero on any failed check. Prints a JSON summary.
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const N = Number(process.env.CLIENTS || 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (s) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s  ${s}`);
const compose = (...args) => execFileSync('docker', ['compose', ...args], { encoding: 'utf8' });
const check = (ok, what) => { if (!ok) throw new Error(`CHECK FAILED: ${what}`); log(`ok: ${what}`); };

async function api(method, path, { session, body, idem, expect = [200, 201] } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (session) headers['X-Convoy-Session'] = session;
  if (idem) headers['Idempotency-Key'] = idem;
  const r = await fetch(GW + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  if (!expect.includes(r.status)) throw new Error(`${method} ${path} -> ${r.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

async function signIn(email) {
  const before = Date.now();
  await api('POST', '/api/auth/otp/start', { body: { email }, expect: [202] });
  for (let i = 0; i < 60; i++) {
    const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json();
    const m = r.messages?.[0];
    const code = m && Date.parse(m.Created) >= before - 2000 ? m.Snippet.match(/\b(\d{6})\b/)?.[1] : null;
    if (code) return (await api('POST', '/api/auth/otp/verify', { body: { email, code } })).session_id;
    await sleep(500);
  }
  throw new Error(`no OTP for ${email}`);
}

/** One SSE client: parses events, reconnects with full-jitter backoff after errors, records what it saw. */
class Client {
  constructor(name, session, pool) {
    Object.assign(this, { name, session, pool, events: [], instance: null, readyAt: 0, closed: false, attempt: 0 });
    this.connect();
  }

  async connect() {
    if (this.closed) return;
    this.ctrl = new AbortController();
    this.lastAt = Date.now();
    clearInterval(this.watchdog);
    // Same watchdog as the web hook: 20 s without an event (heartbeat every 15 s) -> reconnect.
    this.watchdog = setInterval(() => { if (Date.now() - this.lastAt > 20_000) this.ctrl.abort(); }, 1000);
    try {
      const r = await fetch(`${GW}/api/stream/pools/${this.pool}`, { headers: { 'X-Convoy-Session': this.session, accept: 'text/event-stream' }, signal: this.ctrl.signal });
      if (r.status !== 200) throw new Error(`stream ${r.status}`);
      const dec = new TextDecoder();
      let buf = '';
      for await (const chunk of r.body) {
        buf += dec.decode(chunk, { stream: true });
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i);
          buf = buf.slice(i + 2);
          const ev = /^event:(.*)$/m.exec(block)?.[1]?.trim();
          const data = /^data:(.*)$/m.exec(block)?.[1];
          if (!ev || !data) continue;
          const msg = JSON.parse(data);
          this.lastAt = Date.now();
          this.events.push({ ev, msg, at: Date.now() });
          if (ev === 'ready') { this.instance = msg.instance; this.readyAt = Date.now(); this.attempt = 0; }
        }
      }
      throw new Error('stream ended');
    } catch (e) {
      if (this.closed) return;
      this.instance = null;
      const wait = Math.random() * Math.min(30_000, 500 * 2 ** this.attempt++);
      setTimeout(() => this.connect(), wait);
    }
  }

  saw(ev, pred) { return this.events.find((e) => e.ev === ev && pred(e.msg)); }
  close() { this.closed = true; clearInterval(this.watchdog); this.ctrl?.abort(); }
}

async function waitFor(what, fn, ms = 20_000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = fn();
    if (v) return v;
    await sleep(100);
  }
  throw new Error(`timed out: ${what}`);
}

const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)] : null; };
const summary = { clients: N };

// ---- setup: a fresh pool with one member watching through N connections
const tag = Date.now().toString(36);
const owner = await signIn(`rt-owner-${tag}@convoy.test`);
const month = new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + 3, 1)).toISOString().slice(0, 7);
const created = await api('POST', '/api/pools', { session: owner, idem: randomUUID(), body: { model_slug: 'maruti-swift', city: 'Delhi', purchase_month: month } });
const pool = created.pool.id;
await api('POST', `/api/pools/${pool}/members`, { session: owner, idem: randomUUID(), expect: [201, 409] });
log(`pool ${pool}`);

const clients = Array.from({ length: N }, (_, i) => new Client(`c${i}`, owner, pool));
await waitFor('every client ready', () => clients.every((c) => c.instance));
const byInstance = clients.reduce((m, c) => ({ ...m, [c.instance]: (m[c.instance] ?? 0) + 1 }), {});
summary.connections_by_instance = byInstance;
check(Object.keys(byInstance).length === 2, `clients spread over both instances ${JSON.stringify(byInstance)}`);

async function joinAndWait(label, who, expectAll = true) {
  const session = await signIn(`rt-${who}-${tag}@convoy.test`);
  const before = (await api('GET', `/api/pools/${pool}`, { session: owner })).version;
  await api('POST', `/api/pools/${pool}/members`, { session, idem: randomUUID(), expect: [201] });
  const v = before + 1;
  const targets = expectAll ? clients : clients;
  await waitFor(`${label}: v${v} on ${expectAll ? 'every' : 'some'} client`, () =>
    expectAll ? targets.every((c) => c.saw('pool.changed', (m) => m.version === v)) : targets.some((c) => c.saw('pool.changed', (m) => m.version === v)));
  return v;
}

// ---- 2. fan-out across both instances
const v1 = await joinAndWait('fan-out', 'b1');
const got = clients.map((c) => c.saw('pool.changed', (m) => m.version === v1));
check(got.every((e) => e.msg.aggregate === 'pool' && e.msg.id === pool && typeof e.msg.delta.interested_count === 'number'), 'every message carries aggregate, id, version and the new count');
check(got.every((e) => !JSON.stringify(e.msg).includes('buyer_id') && !JSON.stringify(e.msg).includes('membership_id')), 'no buyer or membership identity in any message');
check(clients.every((c) => c.events.filter((e) => e.ev === 'pool.changed' && e.msg.version === v1).length === 1), 'each client got the delta exactly once');
const lags = got.map((e) => e.at - Date.parse(e.msg.occurred_at));
summary.fanout_ms = { p50: pct(lags, 50), p95: pct(lags, 95), max: Math.max(...lags) };
log(`fan-out commit->client ms ${JSON.stringify(summary.fanout_ms)}`);

// ---- 3. Redis FLUSHALL: correctness does not depend on Redis contents
compose('exec', '-T', 'redis', 'redis-cli', 'FLUSHALL');
await joinAndWait('after FLUSHALL', 'b2');
check(true, 'after FLUSHALL the next join still reached every client');

// ---- 4. Redis down: same-instance delivery only, others catch up through the heartbeat version + snapshot
compose('stop', 'redis');
try {
  const v3 = await joinAndWait('redis down', 'b3', false);
  const direct = clients.filter((c) => c.saw('pool.changed', (m) => m.version === v3));
  const missed = clients.filter((c) => !c.saw('pool.changed', (m) => m.version === v3));
  log(`redis down: ${direct.length} clients got v${v3} directly (consuming instance), ${missed.length} missed it`);
  if (missed.length) {
    const hb = await waitFor('missed clients see the newer version in a heartbeat', () =>
      missed.every((c) => c.saw('heartbeat', (m) => m.version >= v3)) && missed.map((c) => c.saw('heartbeat', (m) => m.version >= v3)), 20_000).catch(() => null);
    summary.redis_down = { direct: direct.length, missed: missed.length, heartbeat_carried_version: !!hb };
    check(!!hb, 'every client that missed the delta learned the newer version from a heartbeat (it then refetches the snapshot)');
  } else {
    summary.redis_down = { direct: direct.length, missed: 0 };
  }
  const snap = await api('GET', `/api/pools/${pool}`, { session: owner });
  check(snap.version >= v3, `the snapshot (truth) is at v${snap.version} >= v${v3} regardless of Redis`);
} finally {
  compose('start', 'redis');
}
// Wait until both realtime instances have resubscribed to agg:* (a fixed sleep raced the resubscription and lost the
// event for some clients); the time it took is recorded.
const backAt = Date.now();
for (;;) {
  let n = 0;
  // Connections holding a pattern subscription (PUBSUB NUMPAT would count unique patterns, always 1 here).
  try { n = (compose('exec', '-T', 'redis', 'redis-cli', 'CLIENT', 'LIST').match(/ psub=[1-9]/g) || []).length; } catch { /* Redis still starting */ }
  if (n >= 2 || Date.now() - backAt > 60_000) break;
  await sleep(250);
}
summary.redis_resubscribed_ms = Date.now() - backAt;
await joinAndWait('redis back', 'b4');
check(true, 'Redis back: fan-out to every client resumed');

// ---- 5. lose one instance: graceful stop (deploy) and abrupt kill (crash); its clients reconnect to the survivor
async function lose(how) {
  const counts = clients.reduce((m, c) => ({ ...m, [c.instance]: (m[c.instance] ?? 0) + 1 }), {});
  const victim = Object.keys(counts).sort((a, b) => counts[b] - counts[a])[0];
  const affected = clients.filter((c) => c.instance === victim);
  const at = Date.now();
  compose(how, victim);
  try {
    await waitFor(`${how}: affected clients reconnected`, () => affected.every((c) => c.instance && c.readyAt > at), 90_000);
    const ms = Math.max(...affected.map((c) => c.readyAt - at));
    check(affected.every((c) => c.instance !== victim), `${how} ${victim}: its ${affected.length} clients reconnected to the survivor in ${ms} ms`);
    await joinAndWait(`after ${how}`, `b-${how}`);
    check(true, `after ${how} every client still gets deltas`);
    return ms;
  } finally {
    compose('start', victim);
    await waitFor(`${victim} healthy again`, () => { try { return compose('exec', '-T', victim, 'wget', '-qO-', 'localhost:8080/actuator/health').includes('UP'); } catch { return false; } }, 90_000);
  }
}
summary.reconnect_after_graceful_stop_ms = await lose('stop');
summary.reconnect_after_kill_ms = await lose('kill');

clients.forEach((c) => c.close());
console.log(JSON.stringify(summary));
process.exit(0);
