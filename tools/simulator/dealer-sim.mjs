// Dealer simulator (P3.F, docs/product/dealer-simulator.md). Deterministic: the same seed gives the same plan and the same
// behaviour. It acts ONLY through the public dealer APIs via the gateway (email sign-in, inbox, create/save/submit, revise,
// withdraw, own rank): no internal endpoints, no database writes.
//   import { runDealer } from './dealer-sim.mjs'      (used by the 3-dealer journey)
//   node tools/simulator/dealer-sim.mjs <pool-id> [seed]   (runs every dealer in dealers.json against one pool)
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const DEALERS = JSON.parse(readFileSync(new URL('./dealers.json', import.meta.url), 'utf8'));

/** mulberry32: a tiny seedable PRNG, so a run is reproducible from its seed. */
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const inDays = (d, now) => new Date(now + d * 864e5).toISOString().slice(0, 10);
const round100 = (paise) => Math.round(paise / 10000) * 10000; // whole hundreds of rupees

/**
 * Pure planning (unit-tested): from the round's variants and the dealer's config, the draft it submits, the revision it
 * may make in the final round, and when. Never quotes a variant it holds no stock for, never discounts past its margin
 * floor (max_discount_bp of ex-showroom), and uses the catalogue ex-showroom (verified data) as its price.
 */
export function plan(dealer, variants, seed, now = Date.now()) {
  const r = rng(seed ^ hash(dealer.email));
  const lines = [];
  for (const v of variants) {
    const stock = dealer.inventory.find((i) => i.variant_code === v.code);
    if (!stock || !v.catalogue_ex_showroom_paise) continue;
    lines.push({ v, stock });
  }
  if (lines.length === 0) return null;
  const ex = lines[0].v.catalogue_ex_showroom_paise;
  const floorDiscount = round100((ex * dealer.max_discount_bp) / 10000);
  const s = dealer.strategy;
  const share = s === 'aggressive' ? 0.25 + r() * 0.1 : s === 'conservative' ? 0.4 + r() * 0.2 : 0.5 + r() * 0.2;
  const first = Math.min(floorDiscount, round100(floorDiscount * share));
  const draft = (discount) => ({
    variants: lines.map(({ v, stock }) => ({
      variant_id: v.id, ex_showroom_paise: v.catalogue_ex_showroom_paise, registration_paise: null, insurance: null,
      insurance_addons: [], fees: [], extended_warranty: null, other_charges: [],
      accessories: s === 'misprices_addon'
        ? [{ code: 'MUDFLAPS', label: 'Mudflaps (dealer-required)', amount_paise: dealer.addon_price_paise, basis: 'FIXED', required: true, selected: false, evidence: 'DEALER_CONFIRMED' }]
        : [],
      stock: { units: stock.quantity, colours: [stock.colour], delivery_date: inDays(stock.delivery_days, now) },
    })),
    tiers: [{ min: 1, max: 4, discount_paise: discount }, { min: 5, max: null, discount_paise: Math.min(floorDiscount, discount + round100(ex * 0.005)) }],
    min_fulfilment: 1, max_units: lines.reduce((a, l) => a + l.stock.quantity, 0), valid_until: inDays(30, now),
  });
  return {
    sealed: draft(first),
    // aggressive: undercut in the final round, down to (never past) the margin floor
    final: s === 'aggressive' ? draft(Math.max(first, floorDiscount - round100(ex * 0.005))) : null,
    submitBeforeDeadlineMs: s === 'slow_responder' ? 8000 + Math.floor(r() * 4000) : null, // submit this long before the deadline
    withdraw: s === 'withdraws',
    floorDiscount,
  };
}

function hash(s) {
  let h = 2166136261;
  for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h >>> 0;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Runs one simulated dealer against one pool through the gateway. Returns what it did and the ranks it was shown. */
export async function runDealer(dealer, poolId, { seed = 1, gateway = 'http://localhost:8080', mailpit = 'http://localhost:8025', timeoutMs = 600_000 } = {}) {
  const log = [];
  const ip = `10.250.${hash(dealer.email) % 250}.${(hash(dealer.email) >> 8) % 250}`;
  const call = async (method, path, { session, body, headers = {} } = {}) => {
    const res = await fetch(gateway + path, { method, headers: { 'content-type': 'application/json', 'X-Forwarded-For': ip, ...(session ? { 'X-Convoy-Session': session } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    return { status: res.status, etag: res.headers.get('etag'), json: text ? JSON.parse(text) : null };
  };
  // Email sign-in, exactly as a dealer would (the code arrives in Mailpit locally).
  const before = Date.now();
  await call('POST', '/api/auth/otp/start', { body: { email: dealer.email } });
  let code = null;
  for (let i = 0; i < 60 && !code; i++) {
    await sleep(500);
    const m = (await (await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent('to:' + dealer.email)}&limit=1`)).json()).messages?.[0];
    if (m && Date.parse(m.Created) >= before - 2000) code = m.Snippet.match(/\b(\d{6})\b/)?.[1];
  }
  if (!code) throw new Error(`${dealer.email}: no sign-in code`);
  const session = (await call('POST', '/api/auth/otp/verify', { body: { email: dealer.email, code } })).json.session_id;
  const request = async (kind) => {
    const inbox = await call('GET', '/api/dealer/inbox', { session });
    return inbox.json?.requests?.find((x) => x.pool_id === poolId && x.state === 'OPEN' && (!kind || x.kind === kind));
  };
  const deadline = Date.now() + timeoutMs;
  let req;
  while (!(req = await request('SEALED'))) {
    if (Date.now() > deadline) throw new Error(`${dealer.email}: never invited`);
    await sleep(1000);
  }
  const p = plan(dealer, req.variants, seed);
  if (!p) return { dealer: dealer.email, skipped: 'no stock for this pool', log };
  const created = await call('POST', `/api/dealer/pools/${poolId}/quotes`, { session, headers: { 'Idempotency-Key': randomUUID() } });
  const quoteId = created.json.id;
  let saved = await call('PUT', `/api/dealer/quotes/${quoteId}/draft`, { session, body: p.sealed, headers: { 'If-Match': `"${created.json.version}"` } });
  if (p.submitBeforeDeadlineMs !== null) await sleep(Math.max(0, Date.parse(req.deadline) - Date.now() - p.submitBeforeDeadlineMs));
  const submitted = await call('POST', `/api/dealer/quotes/${quoteId}/submit`, { session, headers: { 'Idempotency-Key': randomUUID(), 'If-Match': `"${saved.json.version}"` } });
  log.push({ step: 'submit', status: submitted.status });
  if (submitted.status !== 200) return { dealer: dealer.email, quoteId, log, error: submitted.json };
  if (p.withdraw) {
    const w = await call('POST', `/api/dealer/quotes/${quoteId}/withdraw`, { session, body: { reason: 'Simulated: stock sold elsewhere' }, headers: { 'Idempotency-Key': randomUUID(), 'If-Match': `"${submitted.json.version}"` } });
    log.push({ step: 'withdraw', status: w.status });
    return { dealer: dealer.email, quoteId, log };
  }
  const ranks = [];
  if (p.final) {
    while (!(await request('FINAL'))) {
      if (Date.now() > deadline) throw new Error(`${dealer.email}: final round never opened`);
      await sleep(250);
    }
    ranks.push({ when: 'final-open', ...(await call('GET', `/api/dealer/quotes/${quoteId}/rank`, { session })).json });
    const current = await call('GET', `/api/dealer/quotes/${quoteId}`, { session });
    const revised = await call('POST', `/api/dealer/quotes/${quoteId}/revisions`, { session, body: p.final, headers: { 'Idempotency-Key': randomUUID(), 'If-Match': current.etag ?? `"${current.json.version}"` } });
    log.push({ step: 'revise', status: revised.status });
    ranks.push({ when: 'after-revision', ...(await call('GET', `/api/dealer/quotes/${quoteId}/rank`, { session })).json });
  }
  return { dealer: dealer.email, quoteId, log, ranks, floorDiscount: p.floorDiscount };
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) {
  const [poolId, seed = '1'] = process.argv.slice(2);
  if (!poolId) { console.error('usage: node tools/simulator/dealer-sim.mjs <pool-id> [seed]'); process.exit(2); }
  const results = await Promise.all(DEALERS.map((d) => runDealer(d, poolId, { seed: Number(seed) }).catch((e) => ({ dealer: d.email, error: String(e) }))));
  console.log(JSON.stringify(results, null, 2));
}
