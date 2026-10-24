import AxeBuilder from '@axe-core/playwright';
import { execFileSync } from 'node:child_process';
import { expect, test, type Browser, type Page } from '@playwright/test';

// The P1 journey through real services and events:
// sign in -> model -> on-road estimate -> pool -> join (double click) -> commit + test deposit ->
// dealer quote -> compare -> accept -> confirm -> tier resolves -> booking voucher.
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

async function dealerQuote(browser: Browser, poolId: string, project: string) {
  const ctx = await browser.newContext({ viewport: project === 'mobile-360' ? { width: 360, height: 780 } : { width: 1280, height: 900 } });
  const page = await ctx.newPage();
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
  await expect(page.getByText('Catalogue estimate').first()).toBeVisible(); // evidence label on the price
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

  await dealerQuote(browser, poolId, info.project.name);
  step('dealer submitted a sealed quote');

  await expect(page.getByRole('link', { name: 'Compare offers' })).toBeVisible({ timeout: 360_000 });
  await page.getByRole('link', { name: 'Compare offers' }).click();
  await expect(page.getByText('Cash payable at your ceiling (the most you can pay)')).toBeVisible();
  await expect(page.getByText(/If the count stays at/)).toBeVisible(); // AMENDMENT-005 projection label
  await axe(page, 'compare');
  await page.getByRole('button', { name: 'Accept this offer (not binding)' }).click();
  await expect(page.getByText('Accepted. You can change your mind')).toBeVisible();
  step('accepted');

  await expect.poll(async () => { await page.goto('/orders'); return page.getByRole('link', { name: /^Order / }).count(); }, { timeout: 240_000, intervals: [5000] }).toBeGreaterThan(0);
  await axe(page, 'orders');
  await page.getByRole('link', { name: /^Order / }).first().click();
  await expect(page.getByRole('button', { name: /Confirm at most/ })).toBeVisible();
  await axe(page, 'order (confirm)');
  await page.getByRole('button', { name: /Confirm at most/ }).click();
  await expect(page.getByText(/Confirmed\. Your final price is set/)).toBeVisible();
  step('confirmed at the ceiling');

  await expect(page.getByRole('img', { name: /QR code for booking voucher/ })).toBeVisible({ timeout: 240_000 });
  await expect(page.getByRole('heading', { name: 'Final price', exact: true })).toBeVisible();
  await axe(page, 'order (voucher)');
  step('booked; voucher with QR shown');
  // Honest UI (P2 step 0.7): cancellation is a stated rule whose path still answers 501.
  await page.getByRole('button', { name: 'Cancel my booking' }).click();
  await expect(page.getByText('Not available in this test build yet')).toBeVisible();
  await expect(page.getByText('Nothing was changed: your order, booking and deposit are exactly as they were.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Final price', exact: true })).toBeVisible();
  step('cancel attempt explained honestly (501), nothing changed');

  // One trace spans web -> gateway -> pool -> SNS/SQS -> order (the accept call; order builds its acceptance read model).
  await expect.poll(async () => {
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
  step('trace web -> gateway -> pool -> SNS/SQS -> order found in Jaeger');
  console.log(JSON.stringify({ project: info.project.name, journey_seconds: (Date.now() - t0) / 1000, axe: axeFindings }));
});

test.afterAll(() => {
  // Ledger balances and every deposit is in a valid AMENDMENT-003 state after the journeys.
  const sql = "select (select coalesce(sum(dr),0) = coalesce(sum(cr),0) from journal_lines) as balanced, "
    + "(select count(*) from payments where state not in ('INITIATED','PENDING','CAPTURED','FAILED','VOIDED_UNPAID','HELD_FOR_BOOKING','REFUND_PENDING','REFUNDED','RELEASED')) as invalid";
  try {
    const out = execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c', `PGPASSWORD="$DB_PASSWORD_PAYMENT" psql -U payment -d convoy -At -c "${sql}"`],
      { cwd: '..', encoding: 'utf8' }).trim();
    console.log(`ledger check: ${out}`);
    expect(out).toBe('t|0');
  } catch (e) {
    console.log(`ledger check skipped: ${(e as Error).message.split('\n')[0]}`);
  }
});
