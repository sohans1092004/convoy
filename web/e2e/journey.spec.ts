import AxeBuilder from '@axe-core/playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
// The simulator lives outside web/ (not in the web image's build context): loaded at run time, only in THREE_DEALERS mode.
const SIMULATOR = '../../tools/simulator/dealer-sim.mjs';
type SimResult = { dealer: string; error?: unknown; log: { step: string; status: number }[]; ranks?: ({ when: string; quote_id: string; ranks: { rank: number; of_n: number }[] } & Record<string, unknown>)[] };

// THREE_DEALERS=1 (nightly): two simulated dealers (aggressive, mispriced add-on) quote next to the Playwright-driven one.
const THREE = process.env.THREE_DEALERS === '1';
// P4.F (nightly): LIFECYCLE=1 carries the booking through to delivery and a review; CANCEL=1 has the dealer cancel after FINAL.
const LIFECYCLE = process.env.LIFECYCLE === '1';
const CANCEL = process.env.CANCEL === '1';
// P6: REMOTE=1 runs the journey against a deployed URL (lite VM, full stack). The checks that read the local compose
// stack (database, metrics, logs, verify-chain) are skipped here and run on the host instead (docs/runbooks/lite-host.md).
const REMOTE = process.env.REMOTE === '1';

/** A query as the payment service's database role (the ledger is checked where it lives). */
function paymentSql(sql: string): string {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c', `PGPASSWORD="$DB_PASSWORD_PAYMENT" psql -U payment -d convoy -At -F '|' -c "${sql}"`],
    { cwd: new URL('../..', import.meta.url), encoding: 'utf8' }).trim();
}

async function dealerSession(browser: Browser, project: string, email: string, act: (page: Page) => Promise<void>) {
  const ctx = await browser.newContext({ viewport: project === 'mobile-360' ? { width: 360, height: 780 } : { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  watch(page);
  await signIn(page, email);
  await act(page);
  await ctx.close();
}

import { expect, test, type Browser, type Page } from '@playwright/test';

// The P1 journey through real services and events:
// sign in -> model -> on-road estimate -> pool -> join (double click) -> commit + test deposit ->
// dealer quote -> compare -> accept -> confirm -> tier resolves -> booking voucher.
// Same guard as scripts/require-razorpay-fake.sh: the journey pays through the fake's checkout, so stop at once on real test mode.
test.beforeAll(() => {
  if (REMOTE) return; // the deployment itself is configured with the simulated payment (P6.B)
  let url = '';
  try {
    url = execFileSync('docker', ['compose', 'exec', '-T', 'payment', 'printenv', 'RAZORPAY_BASE_URL'], { cwd: new URL('../..', import.meta.url), encoding: 'utf8' }).trim();
  } catch { /* reported below */ }
  if (url !== 'http://razorpay-fake:9090') {
    throw new Error(`STOP: payment is not on the local Razorpay fake (RAZORPAY_BASE_URL='${url || 'unreachable'}'). Set RAZORPAY_BASE_URL=http://razorpay-fake:9090 and RAZORPAY_KEY_ID=rzp_test_fake in .env, then docker compose up -d payment web razorpay-fake.`);
  }
});
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const JAEGER = process.env.JAEGER_URL || 'http://localhost:16686';
// Separate accounts per project: parallel sign-ins must never read each other's OTP mail.
const PLAN: Record<string, { buyer: string; dealer: string; model: string; variant: string }> = {
  'mobile-360': { buyer: 'buyer2@convoy.test', dealer: 'dealer1@convoy.test', model: 'Maruti Suzuki Swift', variant: 'LXi MT' },
  desktop: { buyer: 'buyer3@convoy.test', dealer: 'dealer2@convoy.test', model: 'Maruti Suzuki Brezza', variant: 'VXI AT' },
};
const axeFindings: { page: string; ids: string[] }[] = [];

async function axe(page: Page, name: string) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const bad = r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  axeFindings.push({ page: name, ids: bad.map((v) => `${v.id} (${v.nodes.length})`) });
  expect(bad.map((v) => `${v.id}: ${v.help} -> ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`), `axe serious/critical on ${name}`).toEqual([]);
}

async function signIn(page: Page, email: string) {
  await page.goto('/sign-in');
  await axe(page, 'sign-in');
  const before = Date.now();
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Email me a code' }).click();
  let code = '';
  await expect.poll(async () => {
    const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json();
    const m = r.messages?.[0];
    if (m && Date.parse(m.Created) >= before - 2000) code = m.Snippet.match(/\b(\d{6})\b/)?.[1] ?? '';
    return code;
  }, { timeout: 30_000 }).toMatch(/^\d{6}$/);
  await page.getByLabel('6-digit code').fill(code);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/sign-in'), { timeout: 30_000 });
}

// Failure capture (P3): every non-2xx /api response any journey page saw, and on failure the last 2 minutes of
// gateway, identity and web logs, written to reports/p3/failures/ (git-ignored).
const apiFailures: { at: string; status: number; method: string; url: string; body: string }[] = [];
function watch(page: Page) {
  page.on('response', async (r) => {
    if (!r.url().includes('/api/') || r.status() < 400) return;
    let body = '';
    try { body = (await r.text()).slice(0, 2000); } catch { /* streamed or gone */ }
    apiFailures.push({ at: new Date().toISOString(), status: r.status(), method: r.request().method(), url: r.url(), body });
  });
}

test.afterEach(async ({}, info) => {
  if (info.status === info.expectedStatus) return;
  const dir = new URL('../../reports/p3/failures/', import.meta.url);
  mkdirSync(dir, { recursive: true });
  const stamp = `${new Date().toISOString().replace(/[:.]/g, '-')}-${info.project.name}`;
  let logs = '';
  if (!REMOTE) try {
    logs = execFileSync('docker', ['compose', 'logs', '--no-color', '--since', '2m', 'gateway', 'identity', 'web'], { cwd: new URL('../..', import.meta.url), maxBuffer: 64 * 1024 * 1024 }).toString();
  } catch (e) { logs = `could not read docker logs: ${e}`; }
  writeFileSync(new URL(`${stamp}-logs.txt`, dir), logs);
  writeFileSync(new URL(`${stamp}-api-failures.json`, dir), JSON.stringify({ test: info.title, error: info.error?.message ?? null, responses: apiFailures }, null, 2));
});

async function dealerQuote(browser: Browser, poolId: string, project: string) {
  const ctx = await browser.newContext({ viewport: project === 'mobile-360' ? { width: 360, height: 780 } : { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  watch(page);
  await signIn(page, PLAN[project].dealer);
  await page.goto('/dealer');
  const card = page.locator('div', { has: page.getByRole('heading', { name: new RegExp(`Pool …${poolId.slice(-6)}`) }) }).filter({ has: page.getByRole('button') }).last();
  // Client-rendered pages poll every 10 s themselves: wait for the element instead of reloading (a reload shows the skeleton first).
  await expect(card.getByRole('button', { name: 'Start a quote' })).toBeVisible({ timeout: 90_000 });
  await axe(page, 'dealer inbox');
  await card.getByRole('button', { name: 'Start a quote' }).click();
  await page.waitForURL(/\/dealer\/quotes\//);
  await expect(page.getByText('Preview (TypeScript engine')).toBeVisible();
  await axe(page, 'dealer quote form');
  await page.getByRole('button', { name: 'Submit sealed quote' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
  await expect(page.getByText('Quote submitted. It is sealed from other dealers.')).toBeVisible();
  await ctx.close();
}

test('buyer journey from sign-in to booking voucher', async ({ page, browser }, info) => {
  watch(page);
  const plan = PLAN[info.project.name];
  const t0 = Date.now();
  const step = (s: string) => console.log(`[${info.project.name}] ${((Date.now() - t0) / 1000).toFixed(1)}s ${s}`);

  await signIn(page, plan.buyer);
  step('signed in');
  await page.goto('/');
  await axe(page, 'catalogue');
  await page.getByRole('link', { name: plan.model }).click();
  await page.getByRole('radio', { name: new RegExp(plan.variant, 'i') }).check({ timeout: 20_000 });
  await expect(page.getByText('Estimated cash payable')).toBeVisible();
  await expect(page.getByText('Catalogue estimate').filter({ visible: true }).first()).toBeVisible(); // evidence label on the price
  await axe(page, 'model + estimate');
  step('on-road estimate shown');

  await page.getByRole('button', { name: /Join or start a pool in Pune/ }).click();
  await page.waitForURL(/\/pools\/[0-9a-f-]+$/);
  const poolId = page.url().split('/').pop()!;
  await axe(page, 'pool');
  const join = page.getByRole('button', { name: 'Join as interested (free)' });
  await join.dblclick(); // double click: one membership, one count
  await expect(page.getByText('You joined as interested.')).toBeVisible();
  await expect(page.getByText('Status:')).toContainText('interested');
  step(`joined pool ${poolId}`);

  await page.getByRole('checkbox', { name: /I've read these terms/ }).check();
  await page.getByRole('button', { name: 'Commit with ₹999 test deposit' }).click();
  await expect(page.getByRole('dialog', { name: 'Test payment' })).toBeVisible();
  await expect(page.getByText('TEST: no real money').last()).toBeVisible();
  await page.getByRole('button', { name: 'Pay (test)' }).click();
  await expect(page.getByText('You are committed.')).toBeVisible({ timeout: 90_000 });
  step('committed (webhook captured the test deposit)');
  let sims: Promise<SimResult[]> | null = null;
  if (THREE) {
    const { DEALERS, runDealer } = await import(SIMULATOR);
    sims = Promise.all(['aggressive', 'misprices_addon'].map((st) => runDealer(DEALERS.find((d: { strategy: string }) => d.strategy === st), poolId, { seed: 11 }) as Promise<SimResult>));
  }

  await page.goto('/orders');
  const ordersBefore = await page.getByRole('link', { name: /^Order / }).evaluateAll((as) => as.map((a) => a.getAttribute('href')));
  await page.goto(`/pools/${poolId}`);
  await dealerQuote(browser, poolId, info.project.name);
  step('dealer submitted a sealed quote');

  await expect(page.getByRole('link', { name: 'Compare offers' })).toBeVisible({ timeout: 360_000 });
  await page.getByRole('link', { name: 'Compare offers' }).click();
  await expect(page.getByText('Cash payable at your ceiling (the most you can pay)').first()).toBeVisible();
  await expect(page.getByText(/If the count stays at/).first()).toBeVisible(); // AMENDMENT-005 projection label
  if (sims) {
    const [aggressive, mispriced] = await sims;
    step(`simulators: ${JSON.stringify([aggressive, mispriced].map((r) => ({ dealer: r.dealer, log: r.log, error: r.error ?? null })))}`);
    await expect(page.getByRole('button', { name: 'Accept this offer (not binding)' })).toHaveCount(3); // the buyer compares 3 offers
    await expect(page.getByRole('note').filter({ hasText: 'dealer-required add-on' })).toBeVisible(); // the mispriced add-on is flagged
    expect(mispriced.log.map((l: { step: string }) => l.step)).toEqual(['submit']);
    // The aggressive dealer's final-round revision changes the ranks; dealers only ever see their own rank and of_n.
    const [atOpen, afterRevision] = aggressive.ranks!;
    expect(aggressive.log.find((l: { step: string; status: number }) => l.step === 'revise')?.status).toBe(200);
    expect(afterRevision.ranks[0].rank).toBeLessThan(atOpen.ranks[0].rank);
    for (const r of aggressive.ranks!) {
      expect(Object.keys(r).sort()).toEqual(['quote_id', 'ranks', 'when']);
      for (const x of r.ranks) expect(Object.keys(x).sort()).toEqual(['computed_at', 'of_n', 'rank', 'rule_version', 'variant_id']);
    }
    step(`aggressive dealer: rank ${atOpen.ranks[0].rank} of ${atOpen.ranks[0].of_n} at the final-round open, ${afterRevision.ranks[0].rank} after its revision`);
  }
  await axe(page, 'compare');
  // Accept the human dealer's offer (the one built in the quote builder above).
  await page.getByRole('heading', { name: /Convoy Test Motors/ }).locator('xpath=ancestor::*[.//button[contains(., "Accept this offer")]][1]')
    .getByRole('button', { name: 'Accept this offer (not binding)' }).click();
  await expect(page.getByText('Accepted. You can change your mind')).toBeVisible();
  step('accepted');

  // The seeded buyer may hold orders from earlier runs (a reused local stack): open the one this run created.
  let order: string | undefined;
  await expect.poll(async () => {
    await page.goto('/orders');
    const hrefs = await page.getByRole('link', { name: /^Order / }).evaluateAll((as) => as.map((a) => a.getAttribute('href')));
    order = hrefs.find((h) => h && !ordersBefore.includes(h)) ?? undefined;
    return order;
  }, { timeout: 240_000, intervals: [5000] }).toBeTruthy();
  await axe(page, 'orders');
  await page.goto(order!);
  await expect(page.getByRole('button', { name: /Confirm at most/ })).toBeVisible();
  await axe(page, 'order (confirm)');
  await page.getByRole('button', { name: /Confirm at most/ }).click();
  await expect(page.getByText(/Confirmed\. Your final price is set/)).toBeVisible();
  step('confirmed at the ceiling');

  await expect(page.getByRole('img', { name: /QR code for booking voucher/ })).toBeVisible({ timeout: 240_000 });
  await expect(page.getByRole('heading', { name: 'Final price', exact: true })).toBeVisible();
  await axe(page, 'order (voucher)');
  // The buyer's final price never exceeds the ceiling they confirmed.
  const rupeesOf = (t: string) => Number((t.match(/₹([\d,]+)/)?.[1] ?? 'NaN').replace(/,/g, ''));
  const finalPrice = rupeesOf(await page.getByRole('heading', { name: 'Final price', exact: true }).locator('xpath=following-sibling::*[1]').innerText());
  const ceiling = rupeesOf(await page.getByText(/Your ceiling was ₹/).innerText());
  expect(finalPrice).toBeLessThanOrEqual(ceiling);
  step('booked; voucher with QR shown');
  const short = (await page.getByText(/^Booking [0-9A-F]{8}/).first().innerText()).match(/Booking ([0-9A-F]{8})/)![1];
  const deposit = () => paymentSql(`select state from payments where pool_id = '${poolId}' order by created_at desc limit 1`);
  const ledger = () => paymentSql(`select string_agg(e.kind, ',' order by e.kind) from journal_entries e join payments p on p.id = e.payment_id where p.pool_id = '${poolId}'`);
  // Ledger invariants for this deposit: every entry balances, nothing left in 2100/2200, exactly one terminal entry for the full amount.
  const invariants = () => paymentSql(`select (select bool_and(b) from (select sum(l.dr) = sum(l.cr) as b from journal_lines l join journal_entries e on e.id = l.entry_id `
    + `join payments p on p.id = e.payment_id where p.pool_id = '${poolId}' group by l.entry_id) x), (select coalesce(sum(l.cr - l.dr), 0) from journal_lines l join journal_entries e on e.id = l.entry_id `
    + `join payments p on p.id = e.payment_id where p.pool_id = '${poolId}' and l.account in ('2100', '2200')), (select count(*) from journal_entries e join journal_lines l on l.entry_id = e.id and l.line_no = 1 `
    + `join payments p on p.id = e.payment_id where p.pool_id = '${poolId}' and e.kind in ('J-REFUND', 'J-RELEASE', 'J-REFUND-MANUAL') and l.dr = p.amount_paise)`);

  if (LIFECYCLE) {
    await dealerSession(browser, info.project.name, PLAN[info.project.name].dealer, async (d) => {
      await d.goto('/dealer/bookings');
      const card = d.locator(`[aria-label="Booking ${short}"]`);
      await card.getByRole('button', { name: 'Confirm booking' }).click();
      await card.getByRole('button', { name: 'Set delivery date' }).click(); // today (IST) by default
      await expect(card.getByText(/^Delivery \d{4}-\d{2}-\d{2}/)).toBeVisible();
      await axe(d, 'dealer bookings');
      await card.getByRole('button', { name: 'Show buyer contact' }).click(); // released to this dealer only, with the buyer's consent
      await expect(card.getByRole('link', { name: PLAN[info.project.name].buyer })).toBeVisible();
    });
    step('dealer confirmed the booking, set delivery for today, saw the released contact');
    await page.getByRole('button', { name: 'I have my car: confirm delivery' }).click({ timeout: 30_000 });
    await expect(page.getByText('Delivery confirmed. Your deposit is being returned.')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Delivered', exact: true })).toBeVisible({ timeout: 30_000 });
    await expect.poll(deposit, { timeout: 60_000, intervals: [2000] }).toBe('RELEASED'); // AMENDMENT-003
    expect(ledger()).toBe('J-CAPTURE,J-FEE,J-HOLD,J-RELEASE'); // J-FEE: the dealer's Rs 2,500 receivable (AMENDMENT-007), never the deposit
    expect(paymentSql(`select l.account || ':' || l.dr || ':' || l.cr from journal_lines l join journal_entries e on e.id = l.entry_id join payments p on p.id = e.payment_id `
      + `where p.pool_id = '${poolId}' and e.kind = 'J-FEE' order by l.line_no`).split('\n')).toEqual(['1300:250000:0', '4100:0:250000']);
    expect(invariants()).toBe('t|0|1');
    step('delivered; deposit RELEASED in full; fee receivable posted; ledger invariants hold');
    await page.getByLabel('Your review (optional)').fill('Delivered on the day they set.');
    await page.getByRole('button', { name: 'Post review' }).click();
    await expect(page.getByText('Thank you. Your review is posted.')).toBeVisible();
    await axe(page, 'order (delivered)');
    step('review posted');

    // P5.F: the right in-app messages arrived for this run (once each), and each has its email in Mailpit.
    type Note = { kind: string; subject: string; link: string; created_at: string };
    let mine: Note[] = [];
    const want = ['DEPOSIT_RECEIVED', 'COMPARISON_READY', 'CONFIRMATION_NEEDED', 'TIER_RESOLVED', 'BOOKING_CONFIRMED', 'DELIVERY_SCHEDULED'];
    await expect.poll(async () => {
      const r = await (await page.request.get('/api/me/notifications?limit=200')).json();
      mine = (r.notifications as Note[]).filter((n) => Date.parse(n.created_at) >= t0);
      return want.filter((k) => !mine.some((n) => n.kind === k));
    }, { timeout: 60_000, intervals: [2000], message: 'in-app notifications missing' }).toEqual([]);
    for (const k of ['DEPOSIT_RECEIVED', 'CONFIRMATION_NEEDED', 'TIER_RESOLVED', 'BOOKING_CONFIRMED', 'DELIVERY_SCHEDULED']) {
      expect(mine.filter((n) => n.kind === k), `exactly one ${k}`).toHaveLength(1);
    }
    expect(mine.find((n) => n.kind === 'CONFIRMATION_NEEDED')!.link).toBe(order);
    await expect.poll(async () => {
      const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + plan.buyer)}&limit=200`)).json();
      const subjects = (r.messages ?? []).filter((m: { Created: string }) => Date.parse(m.Created) >= t0 - 2000).map((m: { Subject: string }) => m.Subject);
      return mine.filter((n) => !subjects.includes(n.subject)).map((n) => n.kind);
    }, { timeout: 90_000, intervals: [3000], message: 'emails missing in Mailpit' }).toEqual([]);
    step(`notifications: ${mine.length} in-app since the start, each with its email (${[...new Set(mine.map((n) => n.kind))].sort().join(', ')})`);
  }

  if (CANCEL) {
    await dealerSession(browser, info.project.name, 'dealeradmin1@convoy.test', async (d) => {
      await d.goto('/dealer/bookings');
      const card = d.locator(`[aria-label="Booking ${short}"]`);
      await card.getByText('Cancel this booking').click();
      await card.getByRole('button', { name: 'Cancel booking' }).click();
      await expect(card.getByText(/^Cancelled \(DEALER_CANCELLED\)/)).toBeVisible();
    });
    step('the dealer admin cancelled the booking after FINAL');
    await expect(page.getByRole('heading', { name: 'Order ended' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('The dealer cancelled. Your ₹999 test deposit is refunded in full.')).toBeVisible();
    await expect(page.getByText('We opened a support case for you automatically; an admin reviews it.')).toBeVisible();
    await expect.poll(deposit, { timeout: 60_000, intervals: [2000] }).toBe('REFUNDED');
    expect(ledger()).toBe('J-CAPTURE,J-HOLD,J-REFUND');
    expect(invariants()).toBe('t|0|1');
    await axe(page, 'order (cancelled by dealer)');
    await page.getByRole('link', { name: 'Dealer cancelled' }).click();
    await expect(page.getByRole('heading', { name: 'Case: Dealer cancelled' })).toBeVisible();
    await expect(page.getByText('Opened automatically', { exact: false }).first()).toBeVisible(); // also in the description and history
    await axe(page, 'dispute');
    step('buyer refunded in full; dispute opened automatically; ledger invariants hold');
  }

  // One trace spans web -> gateway -> pool -> SNS/SQS -> order (the accept call; order builds its acceptance read model).
  // Needs the tracing profile (Jaeger): CI and nightly set TRACING=1; the lean local stack skips it.
  if (process.env.TRACING === '1') await expect.poll(async () => {
    const q = new URLSearchParams({ 'query.service_name': 'order', 'query.operation_name': 'consume AcceptanceChanged',
      'query.start_time_min': new Date(t0).toISOString(), 'query.start_time_max': new Date(Date.now() + 60_000).toISOString(), 'query.num_traces': '20' });
    const r = await (await fetch(`${JAEGER}/api/v3/traces?${q}`)).json();
    const byTrace = new Map<string, Set<string>>();
    for (const rs of r.result?.resourceSpans ?? []) {
      const svc = rs.resource.attributes.find((a: { key: string }) => a.key === 'service.name')?.value.stringValue;
      for (const ss of rs.scopeSpans) for (const s of ss.spans) (byTrace.get(s.traceId) ?? byTrace.set(s.traceId, new Set()).get(s.traceId)!).add(svc);
    }
    return [...byTrace.values()].some((s) => ['web', 'gateway', 'pool', 'order'].every((x) => s.has(x)));
  }, { timeout: 60_000, intervals: [3000] }).toBe(true);
  if (process.env.TRACING === '1') step('trace web -> gateway -> pool -> SNS/SQS -> order found in Jaeger');
  console.log(JSON.stringify({ project: info.project.name, journey_seconds: (Date.now() - t0) / 1000, axe: axeFindings }));
});

test.afterAll(() => {
  if (REMOTE) { console.log('REMOTE: database, metrics and audit-chain checks are run on the host'); return; }
  // P5.0 version guard: no service dropped an event as stale at a version another event already used.
  // Every consumer must answer: a failed scrape would hide a dropped event (until P5.C every scrape got 401 and counted nothing).
  let distinct = 0;
  const read: string[] = [];
  for (const svc of ['identity', 'pool', 'quote', 'order', 'payment', 'dealer', 'realtime-1', 'notification', 'audit']) {
    const text = execFileSync('docker', ['compose', 'exec', '-T', svc, 'wget', '-qO-', 'localhost:8080/actuator/prometheus'], { cwd: new URL('../..', import.meta.url), encoding: 'utf8' });
    for (const line of text.split('\n')) if (line.startsWith('messaging_stale_distinct')) { distinct += Number(line.split(' ').pop()); read.push(svc); }
  }
  console.log(`messaging.stale_distinct across services: ${distinct} (read from ${read.join(', ')})`);
  expect(distinct).toBe(0);
  expect(read).toHaveLength(9);
  // Ledger balances and every deposit is in a valid AMENDMENT-003 state after the journeys.
  // P5.X: no try/catch (it swallowed a failed expect too) and an empty ledger is not "balanced".
  const sql = "select (select count(*) > 0 and sum(dr) = sum(cr) from journal_lines) as balanced, "
    + "(select count(*) from payments where state not in ('INITIATED','PENDING','CAPTURED','FAILED','VOIDED_UNPAID','HELD_FOR_BOOKING','REFUND_PENDING','REFUNDED','RELEASED')) as invalid";
  const out = execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c', `PGPASSWORD="$DB_PASSWORD_PAYMENT" psql -U payment -d convoy -At -c "${sql}"`],
    { cwd: new URL('../..', import.meta.url), encoding: 'utf8' }).trim();
  console.log(`ledger check: ${out}`);
  expect(out).toBe('t|0');
  // P5.F: the audit hash chain verifies after everything above (exit 1 = tampered; execFileSync throws on it).
  const chain = execFileSync('docker', ['compose', 'run', '--rm', '-T', 'audit', 'verify-chain'], { cwd: new URL('../..', import.meta.url), encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const verdict = chain.split(/\r?\n/).find((l) => l.startsWith('audit chain') || l.startsWith('AUDIT CHAIN')) ?? '';
  console.log(verdict);
  expect(verdict).toMatch(/^audit chain intact: [1-9]\d* rows$/);
});
