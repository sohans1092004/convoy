// Public demo simulated dealers (P6.B). Every simulated dealer in dealers.json watches its inbox and quotes in every new
// sealed round, exactly as the P3.F simulator does, through the public dealer APIs. Sessions come from identity E171
// (client demo-bots, scope demo:sessions) instead of email: the demo has no mailbox. One process, no state but memory:
// after a restart a dealer may try a pool again, and quote rejects the duplicate.
//   GATEWAY_URL IDENTITY_URL CLIENT_SECRET_DEMO_BOTS [POLL_MS]   node tools/simulator/demo-bots.mjs
import { DEALERS, runDealer } from './dealer-sim.mjs';

const GATEWAY = process.env.GATEWAY_URL || 'http://gateway:8080';
const IDENTITY = process.env.IDENTITY_URL || 'http://identity:8080';
const SECRET = process.env.CLIENT_SECRET_DEMO_BOTS;
const POLL_MS = Number(process.env.POLL_MS || 5000);
if (!SECRET) { console.error('CLIENT_SECRET_DEMO_BOTS is required'); process.exit(2); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (msg, extra = {}) => console.log(JSON.stringify({ at: new Date().toISOString(), msg, ...extra }));

async function json(url, init) {
  const r = await fetch(url, init);
  const body = await r.text();
  if (!r.ok) throw new Error(`${init?.method || 'GET'} ${url} -> ${r.status} ${body.slice(0, 200)}`);
  return body ? JSON.parse(body) : null;
}

let token = { value: null, until: 0 };
async function serviceToken() {
  if (Date.now() < token.until - 30_000) return token.value;
  const t = await json(`${IDENTITY}/internal/oauth/token`, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ grant_type: 'client_credentials', client_id: 'demo-bots', client_secret: SECRET, audience: 'identity' }) });
  token = { value: t.access_token, until: Date.now() + t.expires_in * 1000 };
  return token.value;
}

const sessions = new Map(); // email -> { id, until }
async function session(email) {
  const s = sessions.get(email);
  if (s && Date.now() < s.until - 3600_000) return s.id;
  const r = await json(`${IDENTITY}/internal/demo/sessions`, { method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${await serviceToken()}` }, body: JSON.stringify({ email }) });
  sessions.set(email, { id: r.session_id, until: Date.parse(r.session_expires_at) });
  return r.session_id;
}

const started = new Set(); // `${email}|${pool}`
async function tick(dealer) {
  const inbox = await json(`${GATEWAY}/api/dealer/inbox`, { headers: { 'X-Convoy-Session': await session(dealer.email) } });
  for (const req of inbox?.requests ?? []) {
    const key = `${dealer.email}|${req.pool_id}`;
    if (req.kind !== 'SEALED' || req.state !== 'OPEN' || started.has(key)) continue;
    started.add(key);
    log('quoting', { dealer: dealer.email, pool: req.pool_id });
    runDealer(dealer, req.pool_id, { gateway: GATEWAY, seed: Date.now() & 0xffff, timeoutMs: 15 * 60_000, signIn: () => session(dealer.email) })
      .then((r) => log('done', { dealer: dealer.email, pool: req.pool_id, steps: r.log, error: r.error ?? null, skipped: r.skipped ?? null }))
      .catch((e) => log('failed', { dealer: dealer.email, pool: req.pool_id, error: String(e) }));
  }
}

log('demo bots up', { dealers: DEALERS.map((d) => d.email) });
for (;;) {
  for (const d of DEALERS) {
    try {
      await tick(d);
    } catch (e) {
      log('tick failed', { dealer: d.email, error: String(e) });
    }
  }
  await sleep(POLL_MS);
}
