// API-level run of the P1 journey through the gateway (same calls the BFF makes), with timings.
//   node tools/journey/api-journey.mjs            (stack from docker compose, demo durations)
// Buyer signs in -> on-road estimate -> pool -> join -> commit + test deposit -> dealer quote ->
// compare -> accept -> confirm -> tier resolves -> booking voucher. Exits non-zero on any failure.
import { randomUUID } from 'node:crypto';

const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const FAKE = process.env.RAZORPAY_FAKE_URL || 'http://localhost:9090';
const BUYER = process.env.BUYER_EMAIL || 'buyer1@convoy.test';
const DEALER = process.env.DEALER_EMAIL || 'dealer1@convoy.test';
const t0 = Date.now();
const marks = [];
const mark = (label) => { marks.push([label, Date.now() - t0]); console.log(`${String(((Date.now() - t0) / 1000).toFixed(1)).padStart(6)}s  ${label}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, { session, body, idem, ifMatch, expect = [200, 201] } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (session) headers['X-Convoy-Session'] = session;
  if (idem) headers['Idempotency-Key'] = idem;
  if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`;
  const r = await fetch(GW + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (!expect.includes(r.status)) throw new Error(`${method} ${path} -> ${r.status} ${text.slice(0, 400)}`);
  return { status: r.status, data, headers: r.headers };
}

async function until(what, fn, timeoutMs = 300_000, every = 2000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await sleep(every);
  }
}

async function signIn(email) {
  const before = Date.now();
  await api('POST', '/api/auth/otp/start', { body: { email } , expect: [202] });
  const code = await until(`OTP mail for ${email}`, async () => {
    const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json();
    const m = r.messages?.[0];
    if (!m || Date.parse(m.Created) < before - 2000) return null;
    return m.Snippet.match(/\b(\d{6})\b/)?.[1];
  }, 30_000, 500);
  const { data } = await api('POST', '/api/auth/otp/verify', { body: { email, code } });
  return data.session_id;
}

const buyer = await signIn(BUYER);
mark('buyer signed in (email OTP via Mailpit)');

const model = (await api('GET', '/api/catalog/models/maruti-swift', { session: buyer })).data;
const variant = model.variants.find((v) => v.code === 'swift-lxi-mt');
const est = (await api('GET', `/api/catalog/variants/${variant.id}/price-estimate?city=Pune`, { session: buyer })).data;
mark(`on-road estimate Swift LXi, Pune: Rs ${est.snapshot.output.cash_payable_paise / 100} (${est.snapshot.output.warnings.join(', ')})`);

const month = new Date().toISOString().slice(0, 7);
const created = (await api('POST', '/api/pools', { session: buyer, idem: randomUUID(), body: { model_slug: 'maruti-swift', city: 'Pune', purchase_month: month } })).data;
const poolId = created.pool.id;
mark(`pool ${created.created ? 'created' : 'found'}: ${poolId}`);

const joinKey = randomUUID();
const [j1, j2] = await Promise.all([1, 2].map(() => api('POST', `/api/pools/${poolId}/members`, { session: buyer, idem: joinKey, expect: [201, 409] })));
mark(`joined (double click with one key -> ${j1.status}/${j2.status})`);

const pool0 = (await api('GET', `/api/pools/${poolId}`, { session: buyer })).data;
const commit = (await api('POST', `/api/pools/${poolId}/commitment`, { session: buyer, idem: randomUUID(), body: { terms_version: pool0.terms_version } })).data;
const dep = commit.deposit;
mark(`commit -> deposit ${dep.payment_id} (${dep.state}, Rs ${dep.amount_paise / 100}, TEST mode)`);

const cb = await (await fetch(`${FAKE}/fake/pay`, { method: 'POST', body: JSON.stringify({ order_id: dep.razorpay_order_id, outcome: 'success' }) })).json();
await api('POST', `/api/payments/${dep.payment_id}/checkout-callback`, { session: buyer, body: cb });
mark('checkout completed (callback verified -> PENDING; only the webhook can capture)');

await until('membership COMMITTED', async () => (await api('GET', `/api/pools/${poolId}/members/me`, { session: buyer })).data.state === 'COMMITTED');
mark('webhook captured -> PaymentCaptured -> membership COMMITTED');

await until('QUOTE_OPEN', async () => (await api('GET', `/api/pools/${poolId}`, { session: buyer })).data.state === 'QUOTE_OPEN');
mark('pool READY_FOR_QUOTES -> QUOTE_OPEN (dealer eligibility, sealed round opened)');

const dealer = await signIn(DEALER);
const inbox = await until('round in dealer inbox', async () => (await api('GET', '/api/dealer/inbox', { session: dealer })).data.requests.find((r) => r.pool_id === poolId));
mark(`dealer signed in; request in inbox (deadline ${inbox.deadline})`);

const draft = (await api('POST', `/api/dealer/pools/${poolId}/quotes`, { session: dealer, idem: randomUUID(), expect: [201] })).data;
const body = {
  variants: [{ variant_id: variant.id, ex_showroom_paise: 58390000, registration_paise: null, insurance: null,
    accessories: [], fees: [], other_charges: [], stock: { units: 5, colours: ['White'], delivery_date: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10) } }],
  tiers: [{ min: 1, max: 4, discount_paise: 1000000 }, { min: 5, max: null, discount_paise: 2500000 }],
  min_fulfilment: 1, max_units: 5, valid_until: new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10),
};
const saved = (await api('PUT', `/api/dealer/quotes/${draft.id}/draft`, { session: dealer, ifMatch: draft.version, body })).data;
const submitted = (await api('POST', `/api/dealer/quotes/${draft.id}/submit`, { session: dealer, idem: randomUUID(), ifMatch: saved.version })).data;
mark(`dealer quote SUBMITTED (engine-priced tiers: ${submitted.snapshots[variant.id].map((t) => t.cash_payable_paise / 100).join(' / ')})`);

const decision = await until('DECISION_OPEN', async () => {
  const p = (await api('GET', `/api/pools/${poolId}`, { session: buyer })).data;
  return p.state === 'DECISION_OPEN' ? p : null;
}, 600_000, 3000);
mark(`sealed + final round closed -> DECISION_OPEN (decide by ${decision.decision_deadline})`);

const offers = (await api('GET', `/api/pools/${poolId}/quotes`, { session: buyer })).data.quotes;
const offer = offers[0];
const v0 = offer.variants[0];
mark(`compare: ceiling Rs ${v0.ceiling.cash_payable_paise / 100}; "${v0.projection.label}": ${v0.projection.cash_payable_paise === null ? 'n/a' : 'Rs ' + v0.projection.cash_payable_paise / 100}`);

const me = (await api('GET', `/api/pools/${poolId}/members/me`, { session: buyer })).data;
await api('PUT', `/api/pools/${poolId}/members/me/acceptance`, { session: buyer, idem: randomUUID(), ifMatch: me.version, body: { quote_id: offer.quote_id, variant_id: v0.variant_id } });
mark('accepted the offer (non-binding)');

const order = await until('order created', async () => (await api('GET', '/api/me/orders', { session: buyer })).data.orders.find((o) => o.pool_id === poolId), 600_000, 3000);
const detail = (await api('GET', `/api/orders/${order.id}`, { session: buyer })).data;
mark(`decision closed -> order CONFIRMATION_PENDING, ceiling Rs ${detail.snapshots.ceiling.output.cash_payable_paise / 100}`);

await api('POST', `/api/orders/${order.id}/confirm`, { session: buyer, idem: randomUUID(), ifMatch: detail.version, body: { ceiling_snapshot_hash: detail.snapshots.ceiling.sha256 } });
mark('confirmed at the ceiling price');

const booked = await until('booking', async () => {
  const o = (await api('GET', `/api/orders/${order.id}`, { session: buyer })).data;
  return o.state === 'BOOKED' ? o : null;
}, 600_000, 3000);
mark(`tier resolved -> BOOKED, final Rs ${booked.snapshots.final.output.cash_payable_paise / 100} <= ceiling Rs ${booked.snapshots.ceiling.output.cash_payable_paise / 100}`);

const voucher = (await api('GET', `/api/orders/${order.id}/voucher`, { session: buyer })).data;
mark(`voucher issued for booking ${voucher.id} (QR token ${voucher.qr_payload.slice(0, 16)}...)`);

const bookings = await until('dealer booking visible', async () => (await api('GET', '/api/dealer/bookings', { session: dealer })).data.bookings.find((b) => b.order_id === order.id));
mark(`dealer sees booking ${bookings.id} (${bookings.state})`);

console.log(JSON.stringify({ ok: true, pool_id: poolId, order_id: order.id, total_seconds: (Date.now() - t0) / 1000, marks }, null, 0));
