// Contract for the Razorpay fake: the rules Convoy's payment service relies on (Razorpay docs:
// Orders API, Checkout handler signature, webhook signature + event id, Refunds API).
// The same assertions describe what the manual run against real Razorpay test mode must observe.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';

process.env.RAZORPAY_KEY_ID = 'rzp_test_contract';
process.env.RAZORPAY_KEY_SECRET = 'contract_key_secret';
process.env.RAZORPAY_WEBHOOK_SECRET = 'contract_webhook_secret';
process.env.PORT = '0';
const received = [];
const receiver = createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => { received.push({ headers: req.headers, raw: Buffer.concat(chunks).toString() }); res.end('ok'); });
});
let base, fake;
before(async () => {
  await new Promise((r) => receiver.listen(0, r));
  process.env.WEBHOOK_URL = `http://127.0.0.1:${receiver.address().port}/webhooks/razorpay`;
  fake = (await import('./server.mjs')).server;
  await new Promise((r) => fake.listen(0, r));
  base = `http://127.0.0.1:${fake.address().port}`;
});
after(() => { receiver.closeAllConnections(); receiver.close(); fake.closeAllConnections(); fake.close(); });

const auth = { authorization: 'Basic ' + Buffer.from('rzp_test_contract:contract_key_secret').toString('base64'), 'content-type': 'application/json' };
const { hmac } = await import('./server.mjs');
const waitFor = async (pred, ms = 5000) => {
  const until = Date.now() + ms;
  while (!pred()) { if (Date.now() > until) throw new Error('timeout'); await new Promise((r) => setTimeout(r, 50)); }
};

test('orders require basic auth', async () => {
  const r = await fetch(`${base}/v1/orders`, { method: 'POST', body: '{}', headers: { 'content-type': 'application/json' } });
  assert.equal(r.status, 401);
});

test('order -> checkout signature -> signed authorized + captured webhooks -> refund -> refund.processed', async () => {
  const order = await (await fetch(`${base}/v1/orders`, { method: 'POST', headers: auth,
    body: JSON.stringify({ amount: 99900, currency: 'INR', receipt: 'pay-123', notes: { pool_id: 'p1' } }) })).json();
  assert.match(order.id, /^order_/);
  assert.equal(order.entity, 'order');
  assert.equal(order.amount, 99900);
  assert.equal(order.status, 'created');

  const cb = await (await fetch(`${base}/fake/pay`, { method: 'POST', body: JSON.stringify({ order_id: order.id, outcome: 'success' }) })).json();
  assert.equal(cb.razorpay_order_id, order.id);
  assert.equal(cb.razorpay_signature, hmac('contract_key_secret', `${order.id}|${cb.razorpay_payment_id}`));

  await waitFor(() => received.some((w) => JSON.parse(w.raw).event === 'payment.captured'));
  for (const w of received) {
    assert.equal(w.headers['x-razorpay-signature'], hmac('contract_webhook_secret', w.raw), 'webhook signed over raw body');
    assert.match(w.headers['x-razorpay-event-id'], /^[A-Za-z0-9]{14}$/, 'event ids look like the real ones (no prefix)');
  }
  const captured = JSON.parse(received.find((w) => JSON.parse(w.raw).event === 'payment.captured').raw);
  assert.equal(captured.payload.payment.entity.order_id, order.id);
  assert.equal(captured.payload.payment.entity.amount, 99900);
  assert.equal(captured.payload.payment.entity.currency, 'INR');
  assert.deepEqual(received.map((w) => JSON.parse(w.raw).event), ['payment.authorized', 'payment.captured']);

  const refund = await (await fetch(`${base}/v1/payments/${cb.razorpay_payment_id}/refund`, { method: 'POST', headers: auth,
    body: JSON.stringify({ amount: 99900, receipt: 'ref-1', speed: 'normal' }) })).json();
  assert.match(refund.id, /^rfnd_/);
  assert.equal(refund.payment_id, cb.razorpay_payment_id);
  const list = await (await fetch(`${base}/v1/payments/${cb.razorpay_payment_id}/refunds`, { headers: auth })).json();
  assert.equal(list.items[0].receipt, 'ref-1', 'lookup by receipt before retrying a refund');
  await waitFor(() => received.some((w) => JSON.parse(w.raw).event === 'refund.processed'));

  const over = await fetch(`${base}/v1/payments/${cb.razorpay_payment_id}/refund`, { method: 'POST', headers: auth, body: JSON.stringify({ amount: 100 }) });
  assert.equal(over.status, 400, 'cannot refund more than captured');
});

test('failed payment emits payment.failed', async () => {
  const order = await (await fetch(`${base}/v1/orders`, { method: 'POST', headers: auth, body: JSON.stringify({ amount: 99900, currency: 'INR' }) })).json();
  const n = received.length;
  await fetch(`${base}/fake/pay`, { method: 'POST', body: JSON.stringify({ order_id: order.id, outcome: 'fail' }) });
  await waitFor(() => received.length > n);
  assert.equal(JSON.parse(received.at(-1).raw).event, 'payment.failed');
});

test('refunds disabled on the account: generic 400 (as observed on real test mode)', async () => {
  process.env.FAKE_REFUNDS_DISABLED = 'true';
  try {
    const r = await fetch(`${base}/v1/payments/pay_any/refund`, { method: 'POST', headers: auth, body: JSON.stringify({ amount: 99900 }) });
    assert.equal(r.status, 400);
    assert.equal((await r.json()).error.description, 'invalid request sent');
  } finally {
    delete process.env.FAKE_REFUNDS_DISABLED;
  }
});
