import { execFileSync } from 'node:child_process';
import { expect, type Page, test } from '@playwright/test';

// P5.D against the running stack, on the single-dealer journey's buyer (run it after journey.spec.ts):
// export holds every service's part; erasure completes across all services; kept financial rows stay balanced and
// pseudonymised; nothing personal remains in deletable tables. Ends by restarting identity so the DEMO seed re-creates the buyer.
// Opt-in (it erases a demo user): PRIVACY=1 pnpm exec playwright test e2e/privacy.spec.ts --project=desktop
test.skip(process.env.PRIVACY !== '1', 'erases a demo user: set PRIVACY=1');

const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const BUYER = 'buyer2@convoy.test';
const ROOT = new URL('../..', import.meta.url);
const SERVICES = ['catalog', 'dealer', 'identity', 'notification', 'order', 'payment', 'pool', 'quote'];

function sql(q: string): string {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'convoy_admin', '-d', 'convoy', '-At', '-c', q], { cwd: ROOT, encoding: 'utf8' }).trim();
}

async function signIn(page: Page, email: string) {
  await page.goto('/sign-in');
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

test('export holds every service, erasure completes everywhere, money stays balanced', async ({ page }) => {
  test.setTimeout(5 * 60_000);
  await signIn(page, BUYER);
  const csrf = (await page.context().cookies()).find((c) => c.name === 'cv_csrf')?.value ?? '';
  const post = (path: string) => page.request.post(`/api${path}`, { headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': crypto.randomUUID(), Origin: new URL(page.url()).origin } });
  const get = async (path: string) => (await page.request.get(`/api${path}`)).json();

  // a saved car, so catalog has something of this buyer's
  const models = await get('/catalog/models');
  const model = (models.models ?? models)[0];
  expect((await page.request.put(`/api/me/saved-cars/${model.id}`, { headers: { 'X-CSRF-Token': csrf, Origin: new URL(page.url()).origin } })).ok()).toBe(true);

  // ---- export
  const ex = await (await post('/me/data-export')).json();
  let status = ex;
  await expect.poll(async () => (status = await get(`/me/data-export/${ex.export_id}`)).state, { timeout: 120_000, intervals: [2000] }).toBe('READY');
  expect(status.parts_received).toBe(SERVICES.length);
  expect(status.download_url).toBeTruthy();
  const file = await (await fetch(status.download_url)).json();
  expect(Object.keys(file.services).sort()).toEqual(SERVICES);
  expect(file.services.identity.user.email).toBe(BUYER);
  for (const [svc, table] of [['catalog', 'saved_cars'], ['pool', 'memberships'], ['order', 'orders'], ['payment', 'payments'], ['notification', 'notifications']]) {
    expect(file.services[svc][table].length, `${svc}.${table} in the export`).toBeGreaterThan(0);
  }
  const user: string = file.user_id;
  expect(sql(`select count(*) from audit.audit_log where event_type = 'DataExportDownloaded' and payload->>'user_id' = '${user}'`)).toBe('1');

  // ---- erasure (the kept rows are remembered by id, to check they survive under a pseudonym)
  const kept = { payments: sql(`select string_agg(quote_literal(id), ',') from payment.payments where buyer_id = '${user}'`),
    orders: sql(`select string_agg(quote_literal(id), ',') from "order".orders where buyer_id = '${user}'`) };
  expect(kept.payments).not.toBe('');
  const er = await (await post('/me/erasure')).json();
  await expect.poll(() => sql(`select state from identity.erasures where id = '${er.erasure_id}'`), { timeout: 120_000, intervals: [2000] }).toBe('COMPLETED');
  expect(sql(`select string_agg(service, ',' order by service) from identity.erasure_confirmations where erasure_id = '${er.erasure_id}'`))
    .toBe('catalog,dealer,notification,order,payment,pool,quote');

  // nothing personal left where it is deleted ...
  for (const [table, col] of [['catalog.saved_cars', 'buyer_id'], ['pool.preferences', 'buyer_id'], ['notification.notifications', 'user_id'],
    ['notification.preferences', 'user_id'], ['notification.push_subscriptions', 'user_id'], ['identity.user_roles', 'user_id'], ['identity.sessions', 'user_id'],
    ['identity.passkeys', 'user_id'], ['identity.consents', 'user_id']]) {
    expect(sql(`select count(*) from ${table} where ${col} = '${user}'`), table).toBe('0');
  }
  expect(sql(`select count(*) from notification.events where payload::text like '%${user}%'`)).toBe('0');
  expect(sql(`select email_hash like 'erased:%' and length(email_enc) = 0 and status = 'ERASED' from identity.users where id = '${user}'`)).toBe('t');
  // ... and where it is kept, the rows are there under a pseudonym (no column or outbox payload names the person)
  for (const t of ['payment.payments', '"order".orders', 'pool.memberships', 'quote.member_replica', '"order".disputes', 'dealer.reviews']) {
    expect(sql(`select count(*) from ${t} x where x::text like '%${user}%'`), t).toBe('0');
  }
  for (const s of ['pool', 'order', 'payment', 'quote', 'dealer']) {
    expect(sql(`select count(*) from "${s}".outbox where payload::text like '%${user}%'`), `${s}.outbox`).toBe('0');
  }
  expect(sql(`select count(*) from payment.payments where id in (${kept.payments})`)).toBe(String(kept.payments.split(',').length));
  if (kept.orders) expect(sql(`select count(*) from "order".orders where id in (${kept.orders})`)).toBe(String(kept.orders.split(',').length));
  // money rule: the ledger still balances and every deposit is in a valid AMENDMENT-003 state
  expect(sql("select (select coalesce(sum(dr),0) = coalesce(sum(cr),0) from payment.journal_lines) || '|' || (select count(*) from payment.payments where state not in "
    + "('INITIATED','PENDING','CAPTURED','FAILED','VOIDED_UNPAID','HELD_FOR_BOOKING','REFUND_PENDING','REFUNDED','RELEASED'))")).toBe('true|0');
  // the export file is gone with the account
  expect(sql(`select count(*) from identity.data_exports where user_id = '${user}' and object_key is not null`)).toBe('0');
});

test.afterAll(() => {
  if (process.env.PRIVACY === '1') execFileSync('docker', ['compose', 'restart', 'identity'], { cwd: ROOT }); // DEMO seed re-creates buyer2
});
