// Razorpay TEST-mode fake for local runs and CI (no dependencies, Node stdlib only).
//
// Implements the subset Convoy uses, with Razorpay's request/response shapes and signatures:
//   POST /v1/orders                      Basic auth key_id:key_secret
//   GET  /v1/orders/:id/payments
//   POST /v1/payments/:id/refund         -> refund (status pending) + later refund.processed webhook
//   GET  /v1/payments/:id/refunds
//   GET  /checkout.js                    a stand-in for checkout.razorpay.com/v1/checkout.js
//   POST /fake/pay {order_id, outcome}   what the stand-in checkout calls ("success" | "fail")
//   POST /fake/webhooks/replay {event_id}  re-send a webhook (duplicate-delivery tests)
// Webhooks are signed exactly like Razorpay: X-Razorpay-Signature = hex HMAC-SHA256(raw body, webhook secret),
// with an x-razorpay-event-id header. Checkout signature = hex HMAC-SHA256(order_id + "|" + payment_id, key secret).
// contract.test.mjs checks these rules; payment's tests run against this server.
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

const KEY_ID = process.env.RAZORPAY_KEY_ID || 'rzp_test_fake';
const KEY_SECRET = process.env.RAZORPAY_KEY_SECRET || 'fake_key_secret';
const WEBHOOK_SECRET = process.env.RAZORPAY_WEBHOOK_SECRET || 'fake_webhook_secret';
// Read at delivery time, not import time (the contract test sets it after importing).
const webhookUrl = () => process.env.WEBHOOK_URL || '';
const PORT = Number(process.env.PORT || 9090);
const ACCOUNT = 'acc_TestConvoyFake';

const orders = new Map();   // id -> order
const payments = new Map(); // id -> payment
const refunds = new Map();  // id -> refund
const sent = new Map();     // event_id -> {body, signature}

const id = (prefix) => `${prefix}_${randomBytes(7).toString('base64url').replace(/[-_]/g, 'x').slice(0, 14)}`;
const now = () => Math.floor(Date.now() / 1000);
export const hmac = (secret, data) => createHmac('sha256', secret).update(data).digest('hex');

function json(res, status, body, extra = {}) {
  res.writeHead(status, { 'content-type': 'application/json', 'access-control-allow-origin': '*', ...extra });
  res.end(JSON.stringify(body));
}

function error(res, status, description) {
  json(res, status, { error: { code: status === 401 ? 'BAD_REQUEST_ERROR' : 'BAD_REQUEST_ERROR', description } });
}

function authed(req) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Basic ')) return false;
  const want = Buffer.from(`${KEY_ID}:${KEY_SECRET}`);
  const got = Buffer.from(Buffer.from(h.slice(6), 'base64').toString());
  return got.length === want.length && timingSafeEqual(got, want);
}

async function body(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString();
  return raw ? JSON.parse(raw) : {};
}

async function webhook(event, entityName, entity) {
  const eventId = id('evt');
  const payload = { entity: 'event', account_id: ACCOUNT, event, contains: [entityName],
    payload: { [entityName]: { entity } }, created_at: now() };
  const raw = JSON.stringify(payload);
  const signature = hmac(WEBHOOK_SECRET, raw);
  sent.set(eventId, { raw, signature });
  await deliver(eventId);
  return eventId;
}

async function deliver(eventId) {
  const WEBHOOK_URL = webhookUrl();
  if (!WEBHOOK_URL) return;
  const { raw, signature } = sent.get(eventId);
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const r = await fetch(WEBHOOK_URL, { method: 'POST', body: raw,
        headers: { 'content-type': 'application/json', 'x-razorpay-signature': signature, 'x-razorpay-event-id': eventId } });
      if (r.status < 500) return;
    } catch { /* receiver not up yet */ }
    await new Promise((r) => setTimeout(r, 500 * 2 ** attempt));
  }
}

function checkoutJs() {
  // Minimal modal with the same constructor/handler contract as Razorpay Checkout.
  return `(function(){
  var BASE = ${JSON.stringify(`http://localhost:${PORT}`)};
  function Razorpay(opts){ this.opts = opts; }
  Razorpay.prototype.on = function(name, fn){ this['on_' + name] = fn; return this; };
  Razorpay.prototype.open = function(){
    var self = this, o = this.opts;
    var d = document.createElement('div');
    d.setAttribute('role','dialog'); d.setAttribute('aria-modal','true'); d.setAttribute('aria-label','Test payment');
    d.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.55);display:grid;place-items:center;z-index:9999';
    d.innerHTML = '<div style="background:#fff;color:#111;padding:24px;border-radius:12px;max-width:340px;font:16px system-ui">'
      + '<p style="margin:0 0 4px;font-weight:700">TEST: no real money</p>'
      + '<p style="margin:0 0 16px">Razorpay test-mode stand-in. Pay \\u20b9' + (o.amount/100).toFixed(0) + ' for ' + (o.description||'') + '?</p>'
      + '<button data-outcome="success" style="min-height:44px;padding:0 16px;margin-right:8px">Pay (test)</button>'
      + '<button data-outcome="fail" style="min-height:44px;padding:0 16px">Fail payment</button></div>';
    document.body.appendChild(d);
    d.querySelector('button').focus();
    d.addEventListener('click', function(e){
      var outcome = e.target && e.target.getAttribute('data-outcome'); if (!outcome) return;
      fetch(BASE + '/fake/pay', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({order_id: o.order_id, outcome: outcome})})
        .then(function(r){ return r.json(); }).then(function(r){
          document.body.removeChild(d);
          if (outcome === 'success') o.handler(r);
          else if (self.on_payment_failed) self.on_payment_failed({error: r.error});
        });
    });
  };
  window.Razorpay = Razorpay;
})();`;
}

export const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  try {
    if (req.method === 'OPTIONS') return json(res, 204, {}, { 'access-control-allow-headers': 'content-type', 'access-control-allow-methods': 'POST, GET' });
    if (req.method === 'GET' && p === '/health') return json(res, 200, { status: 'ok' });
    if (req.method === 'GET' && p === '/checkout.js') {
      res.writeHead(200, { 'content-type': 'application/javascript', 'access-control-allow-origin': '*' });
      return res.end(checkoutJs());
    }
    if (req.method === 'POST' && p === '/fake/pay') {
      const { order_id, outcome } = await body(req);
      const order = orders.get(order_id);
      if (!order) return error(res, 400, 'unknown order');
      const pid = id('pay');
      const payment = { id: pid, entity: 'payment', amount: order.amount, currency: order.currency, status: 'authorized',
        order_id, method: 'upi', captured: false, email: 'void@razorpay.com', contact: '+919999999999', created_at: now(), notes: order.notes };
      payments.set(pid, payment);
      order.attempts += 1;
      if (outcome === 'fail') {
        Object.assign(payment, { status: 'failed', error_code: 'BAD_REQUEST_ERROR', error_description: 'Payment failed (test)' });
        setTimeout(() => webhook('payment.failed', 'payment', payment), 50);
        return json(res, 200, { error: { code: 'BAD_REQUEST_ERROR', description: 'Payment failed (test)', metadata: { order_id, payment_id: pid } } });
      }
      // Auto-capture is on for Convoy's account (deposit-contract.md §1): authorized then captured.
      setTimeout(async () => {
        await webhook('payment.authorized', 'payment', { ...payment });
        Object.assign(payment, { status: 'captured', captured: true });
        order.status = 'paid'; order.amount_paid = order.amount; order.amount_due = 0;
        await webhook('payment.captured', 'payment', { ...payment });
      }, 300);
      return json(res, 200, { razorpay_payment_id: pid, razorpay_order_id: order_id, razorpay_signature: hmac(KEY_SECRET, `${order_id}|${pid}`) });
    }
    if (req.method === 'POST' && p === '/fake/webhooks/replay') {
      const { event_id } = await body(req);
      if (!sent.has(event_id)) return error(res, 404, 'unknown event');
      await deliver(event_id);
      return json(res, 200, { replayed: event_id });
    }
    if (req.method === 'GET' && p === '/fake/events') {
      return json(res, 200, { events: [...sent.entries()].map(([k, v]) => ({ id: k, event: JSON.parse(v.raw).event })) });
    }

    if (!p.startsWith('/v1/')) return error(res, 404, 'not found');
    if (!authed(req)) return error(res, 401, 'The api key provided is invalid');

    if (req.method === 'POST' && p === '/v1/orders') {
      const b = await body(req);
      if (!Number.isInteger(b.amount) || b.amount < 100) return error(res, 400, 'The amount must be atleast INR 1.00');
      if (b.currency !== 'INR') return error(res, 400, 'Currency is not supported');
      const order = { id: id('order'), entity: 'order', amount: b.amount, amount_paid: 0, amount_due: b.amount, currency: b.currency,
        receipt: b.receipt ?? null, offer_id: null, status: 'created', attempts: 0, notes: b.notes ?? [], created_at: now() };
      orders.set(order.id, order);
      return json(res, 200, order);
    }
    let m;
    if (req.method === 'GET' && (m = p.match(/^\/v1\/orders\/([^/]+)\/payments$/))) {
      if (!orders.has(m[1])) return error(res, 400, 'The id provided does not exist');
      const items = [...payments.values()].filter((x) => x.order_id === m[1]);
      return json(res, 200, { entity: 'collection', count: items.length, items });
    }
    if (req.method === 'POST' && (m = p.match(/^\/v1\/payments\/([^/]+)\/refund$/))) {
      const payment = payments.get(m[1]);
      if (!payment || payment.status !== 'captured') return error(res, 400, 'The payment has not been captured');
      const b = await body(req);
      const amount = b.amount ?? payment.amount;
      const already = [...refunds.values()].filter((r) => r.payment_id === payment.id).reduce((s, r) => s + r.amount, 0);
      if (already + amount > payment.amount) return error(res, 400, 'The refund amount provided is greater than amount captured');
      const refund = { id: id('rfnd'), entity: 'refund', amount, currency: payment.currency, payment_id: payment.id, notes: b.notes ?? [],
        receipt: b.receipt ?? null, acquirer_data: { arn: null }, created_at: now(), batch_id: null, status: 'pending',
        speed_processed: 'normal', speed_requested: b.speed ?? 'normal' };
      refunds.set(refund.id, refund);
      setTimeout(async () => {
        refund.status = 'processed';
        await webhook('refund.processed', 'refund', { ...refund });
      }, 300);
      return json(res, 200, refund);
    }
    if (req.method === 'GET' && (m = p.match(/^\/v1\/payments\/([^/]+)\/refunds$/))) {
      const items = [...refunds.values()].filter((r) => r.payment_id === m[1]);
      return json(res, 200, { entity: 'collection', count: items.length, items });
    }
    return error(res, 404, 'not found');
  } catch (e) {
    return error(res, 500, String(e));
  }
});

if (process.argv[1] && process.argv[1].endsWith('server.mjs')) {
  server.listen(PORT, () => console.log(`razorpay-fake listening on :${PORT} (TEST mode stand-in), webhooks -> ${webhookUrl() || '(none)'}`));
}
