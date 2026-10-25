// AZ-T6 (authz-matrix.md): a dealer admin revokes staff S at t0 (E012); S polls E060 every 1 s and holds a live pool
// stream. Pass = first API rejection <= t0 + 30 s, zero successes after it, stream closed <= t0 + 30 s.
// Writes reports/p2/revocation.json.   node tools/revocation/az-t6.mjs   (stack running; GATEWAY_URL, MAILPIT_URL)
// Needs a pool that S's dealership is invited to: an open request in its inbox, or POOL_ID. S = dealer3@convoy.test, re-seeded
// as active staff whenever identity restarts.
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const DEALERSHIP = '0190f3a0-0000-7000-8000-00000000d001';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function signIn(email) {
  const before = Date.now();
  const r = await fetch(`${GW}/api/auth/otp/start`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email }) });
  if (r.status !== 202) throw new Error(`otp start ${email}: ${r.status}`);
  for (let i = 0; i < 60; i++) {
    const m = (await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json()).messages?.[0];
    const code = m && Date.parse(m.Created) >= before - 2000 ? m.Snippet.match(/\b(\d{6})\b/)?.[1] : null;
    if (code) {
      const v = await fetch(`${GW}/api/auth/otp/verify`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, code }) });
      const b = await v.json();
      if (!v.ok) throw new Error(`verify ${email}: ${v.status}`);
      return { session: b.session_id, userId: b.user.id, roles: b.user.roles };
    }
    await sleep(500);
  }
  throw new Error(`no OTP mail for ${email}`);
}

const get = (path, session) => fetch(GW + path, { headers: { 'X-Convoy-Session': session } });

const admin = await signIn('dealeradmin1@convoy.test');
const staff = await signIn('dealer3@convoy.test');
if (!staff.roles.includes('dealer_staff')) throw new Error('dealer3 is not active staff: restart identity to re-seed it');
const inbox = await (await get('/api/dealer/inbox', staff.session)).json();
const poolId = process.env.POOL_ID || (inbox.requests ?? []).map((x) => x.pool_id).find(Boolean);
if (!poolId) throw new Error('no open request in the dealer inbox: run the P1 journey, or set POOL_ID to a pool the dealership was invited to');

// Live stream (E120) as S.
const ctl = new AbortController();
const stream = await fetch(`${GW}/api/stream/pools/${poolId}`, { headers: { 'X-Convoy-Session': staff.session, accept: 'text/event-stream' }, signal: ctl.signal });
if (!stream.ok) throw new Error(`stream ${stream.status}`);
let streamClosedAt = null;
let lastEvent = null;
(async () => {
  const reader = stream.body.getReader();
  const dec = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const m = dec.decode(value).match(/event: (\w[\w.]*)/g);
      if (m) lastEvent = m.at(-1).replace(/^event: ?/, "");
    }
  } catch { /* aborted */ }
  streamClosedAt ??= Date.now();
})();

// Poll E060 every 1 s.
const polls = [];
let polling = true;
(async () => {
  while (polling) {
    const at = Date.now();
    const r = await get('/api/dealer/inbox', staff.session).catch(() => ({ status: 0 }));
    polls.push({ at, status: r.status });
    await sleep(Math.max(0, 1000 - (Date.now() - at)));
  }
})();

await sleep(3000); // a few successful polls before t0
const t0 = Date.now();
const rev = await fetch(`${GW}/api/dealerships/${DEALERSHIP}/staff/${staff.userId}`, { method: 'DELETE', headers: { 'X-Convoy-Session': admin.session, 'Idempotency-Key': randomUUID() } });
if (!rev.ok) throw new Error(`revoke ${rev.status} ${await rev.text()}`);
while (Date.now() - t0 < 45_000 && !(streamClosedAt && polls.some((p) => p.at > t0 && p.status !== 200))) await sleep(250);
await sleep(5000); // keep polling: any success after the first rejection fails the test
polling = false;
ctl.abort();

const firstReject = polls.find((p) => p.at >= t0 && p.status !== 200);
const okAfterReject = firstReject ? polls.filter((p) => p.at > firstReject.at && p.status === 200).length : null;
const result = {
  measured_at: new Date().toISOString(),
  commit: execFileSync('git', ['rev-parse', '--short', 'HEAD']).toString().trim(),
  env: 'local docker compose (lite), gateway session cache TTL 25 s, realtime revalidate 15 s',
  api_first_rejection_ms: firstReject ? firstReject.at - t0 : null,
  api_rejection_status: firstReject?.status ?? null,
  api_successes_after_first_rejection: okAfterReject,
  stream_closed_ms: streamClosedAt ? streamClosedAt - t0 : null,
  stream_last_event: lastEvent,
  polls_total: polls.length,
};
result.pass = result.api_first_rejection_ms !== null && result.api_first_rejection_ms <= 30_000 && okAfterReject === 0
  && result.stream_closed_ms !== null && result.stream_closed_ms <= 30_000;
writeFileSync(new URL('../../reports/p2/revocation.json', import.meta.url), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
process.exit(result.pass ? 0 : 1);
