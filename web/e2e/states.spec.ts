import { expect, test, type Page, type Route } from '@playwright/test';
import { readFileSync } from 'node:fs';

const pricing = JSON.parse(readFileSync(new URL('../../spec-tools/vectors/pricing.json', import.meta.url), 'utf8'));

const P050 = (pricing as { cases: { id: string; expected: unknown }[] }).cases.find((c) => c.id === 'P050')!.expected;

// PLAN §5.2: the eight states on the discover, join and pool pages, in the running app. Browser calls to /api are
// answered by page.route (the model page's server render still uses the real catalogue). A placeholder session cookie
// passes the route guard; every authorised call it would make is mocked.
type Reply = { status: number; body?: unknown } | 'hang';

async function mock(page: Page, routes: Record<string, Reply[]>) {
  const calls: Record<string, number> = {};
  await page.route('**/api/**', async (route: Route) => {
    const req = route.request();
    const u = new URL(req.url());
    const sig = `${req.method()} ${u.pathname}${u.search}`;
    const key = Object.keys(routes).find((k) => new RegExp(k).test(sig));
    if (!key) return route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ status: 404, detail: `unmocked ${sig}` }) });
    const list = routes[key];
    const reply = list[Math.min((calls[key] = (calls[key] ?? -1) + 1), list.length - 1)];
    if (reply === 'hang') return; // never answered: loading / pending
    if (u.pathname.startsWith('/api/stream/') && reply.status === 200) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: `event: ready\ndata: ${JSON.stringify({ version: 0, server_time: new Date().toISOString() })}\n\n` });
    }
    return route.fulfill({ status: reply.status, contentType: reply.status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(reply.body ?? {}) });
  });
}

const ok = (body: unknown) => ({ status: 200, body });
const problem = (status: number, code: string, detail: string) => ({ status, body: { status, code, detail, title: detail } });

async function asBuyer(page: Page) {
  const base = new URL(test.info().project.use.baseURL ?? 'http://localhost:3000');
  await page.context().addCookies([
    { name: 'cv_sid', value: 'placeholder-session', domain: base.hostname, path: '/' },
    { name: 'cv_roles', value: 'buyer', domain: base.hostname, path: '/' },
  ]);
}

// Verified catalogue rows (same as the Storybook fixtures).
const MODELS = {
  ruleset_version: '2026.10.24-p0', cities: ['Mumbai', 'Pune', 'Delhi', 'Bengaluru'],
  models: [
    { id: '95df624e-9cb0-5225-8d19-8f22a14c0841', slug: 'hyundai-creta', name: 'Creta', brand: 'Hyundai', body_type: 'SUV', variant_count: 5, from_ex_showroom_paise: 109070000, any_city_confirmed: false, evidence: 'CATALOGUE_ESTIMATE' },
    { id: '980eec82-f8d9-545d-a6f3-e56f84915c51', slug: 'mg-hector', name: 'Hector', brand: 'MG Motor', body_type: 'SUV', variant_count: 4, from_ex_showroom_paise: 121400000, any_city_confirmed: false, evidence: 'CATALOGUE_ESTIMATE' },
  ],
};
const deadline = () => new Date(Date.now() + 3 * 864e5).toISOString();
const pool = (over: Record<string, unknown> = {}) => ({
  id: 'p1', model_slug: 'maruti-swift', brand: 'Maruti Suzuki', city: 'Pune', state_code: 'MH', accepted_variant_ids: [], min_members: 5, state: 'FORMING',
  end_reason: null, round_set: 1, purchase_month: '2026-11', forming_deadline: deadline(), decision_deadline: null, confirmation_deadline: null,
  purchase_window_end: deadline(), interested_count: 4, committed_count: 2, version: 7, server_time: new Date().toISOString(), rounds: [],
  deposit_amount_paise: 99900, terms_version: '2026-10-24-p0', ...over,
});
const MEMBER = { id: 'mem1', pool_id: 'p1', state: 'INTERESTED', current_payment_id: null, terms_version: null, accepted_quote_id: null, accepted_variant_id: null, release_reason: null, version: 1 };

// ---------------------------------------------------------------- discover
test.describe('discover states', () => {
  const list = 'GET /api/catalog/models$';
  test('loading', async ({ page }) => {
    await mock(page, { [list]: ['hang'] });
    await page.goto('/');
    await expect(page.getByRole('status', { name: 'Loading cars' })).toBeVisible();
  });
  test('empty', async ({ page }) => {
    await mock(page, { [list]: [ok(MODELS)] });
    await page.goto('/');
    await page.getByLabel('Search cars').fill('zzz');
    await expect(page.getByText(/No cars match/)).toBeVisible();
  });
  test('error keeps the search', async ({ page }) => {
    await mock(page, { [list]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] });
    await page.goto('/');
    await page.getByLabel('Search cars').fill('creta');
    await expect(page.getByRole('alert').filter({ hasText: 'search is kept' })).toBeVisible();
    await expect(page.getByLabel('Search cars')).toHaveValue('creta');
  });
  test('stale', async ({ page }) => {
    // Loaded, browsed Pune, came back: the refresh of the cached list fails, so it is shown as stale.
    await mock(page, { 'GET /api/catalog/models\\?city=Pune': [ok(MODELS)], [list]: [ok(MODELS), problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] });
    await page.goto('/');
    await expect(page.getByText('Creta', { exact: false })).toBeVisible();
    await page.getByLabel('City').selectOption('Pune');
    await expect(page.getByText(/2 cars · Pune/)).toBeVisible();
    await page.getByLabel('City').selectOption('');
    await expect(page.getByText(/latest refresh failed/)).toBeVisible();
    await expect(page.getByText('Creta', { exact: false })).toBeVisible();
  });
  test('pending', async ({ page }) => {
    await mock(page, { 'GET /api/catalog/models\\?city=Pune': ['hang'], [list]: [ok(MODELS)] });
    await page.goto('/');
    await expect(page.getByText('Creta', { exact: false })).toBeVisible();
    await page.getByLabel('City').selectOption('Pune');
    await expect(page.getByText(/Updating prices for Pune/)).toBeVisible();
  });
  test('conflict (prices recalculated while browsing)', async ({ page }) => {
    await mock(page, { 'GET /api/catalog/models\\?city=Pune': [ok({ ...MODELS, ruleset_version: '2026.11.01-p1' })], [list]: [ok(MODELS)] });
    await page.goto('/');
    await expect(page.getByText('Creta', { exact: false })).toBeVisible();
    await page.getByLabel('City').selectOption('Pune');
    await expect(page.getByText(/recalculated while you browsed/)).toBeVisible();
  });
  test('offline', async ({ page, context }) => {
    await mock(page, { [list]: [ok(MODELS)] });
    await page.goto('/');
    await expect(page.getByText('Creta', { exact: false })).toBeVisible();
    await context.setOffline(true);
    await expect(page.getByText(/You are offline/)).toBeVisible();
    await context.setOffline(false);
  });
  test('success', async ({ page }) => {
    await mock(page, { [list]: [ok(MODELS)] });
    await page.goto('/');
    await expect(page.getByText(/2 cars/)).toBeVisible();
  });
});

// ---------------------------------------------------------------- join (model page)
test.describe('join states', () => {
  const est = 'GET /api/catalog/variants/[^/]+/price-estimate';
  // Engine output of pricing vector P050 (spec-tools/vectors), a verified case: the same fixture as the Storybook stories.
  const estimate = async () => ({
    variant: { id: '96a9e043-f401-5b03-b82a-52e617913ce5', code: 'swift-lxi-mt', name: 'LXi MT' }, city: 'Pune', city_confirmed: false,
    source: { url: 'https://www.marutisuzuki.com/arena/swift/price', accessed_on: '2026-10-24' },
    snapshot: { inputs: {}, ruleset_version: '2026.10.24-p0', output: P050, sha256: 'a'.repeat(64), computed_at: '2026-10-24T06:00:00Z' },
  });
  test.beforeEach(async ({ page }) => asBuyer(page));
  test('loading', async ({ page }) => {
    await mock(page, { [est]: ['hang'] });
    await page.goto('/models/maruti-swift');
    await expect(page.getByRole('status', { name: 'Calculating the on-road estimate' })).toBeVisible();
  });
  test('empty (no verified price here)', async ({ page }) => {
    await mock(page, { [est]: [problem(422, 'QUOTE_INVALID', 'RTO_UNVERIFIED')] });
    await page.goto('/models/maruti-swift');
    await expect(page.getByText(/No verified price for/)).toBeVisible();
  });
  test('error keeps the choices', async ({ page }) => {
    await mock(page, { [est]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] });
    await page.goto('/models/maruti-swift');
    await page.getByLabel('City').selectOption('Delhi');
    await expect(page.getByRole('alert').filter({ hasText: 'Your variant and city are kept' })).toBeVisible();
    await expect(page.getByLabel('City')).toHaveValue('Delhi');
  });
  test('stale', async ({ page }) => {
    const e = await estimate();
    // Pune loaded, Delhi looked at, back to Pune: refreshing Pune's estimate fails, so the cached one is shown as stale.
    await mock(page, { [`${est}\\?city=Delhi`]: [ok({ ...e, city: 'Delhi' })], [est]: [ok(e), problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] });
    await page.goto('/models/maruti-swift');
    await expect(page.getByText('Estimated cash payable')).toBeVisible();
    await page.getByLabel('City').selectOption('Delhi');
    await expect(page.getByRole('heading', { name: 'On-road estimate in Delhi' })).toBeVisible();
    await page.getByLabel('City').selectOption('Pune');
    await expect(page.getByText(/could not be refreshed/)).toBeVisible();
  });
  test('pending', async ({ page }) => {
    await mock(page, { [est]: [ok(await estimate())], 'POST /api/pools': ['hang'] });
    await page.goto('/models/maruti-swift');
    await page.getByRole('button', { name: /Join or start a pool/ }).click();
    await expect(page.getByRole('button', { name: /Join or start a pool/ })).toHaveAttribute('aria-busy', 'true');
  });
  test('conflict', async ({ page }) => {
    await mock(page, { [est]: [ok(await estimate())], 'POST /api/pools': [problem(409, 'ACTIVE_COMMITMENT_EXISTS', 'You already hold a commitment for this model in another pool.')] });
    await page.goto('/models/maruti-swift');
    await page.getByRole('button', { name: /Join or start a pool/ }).click();
    await expect(page.getByText(/already hold a commitment/)).toBeVisible();
  });
  test('offline', async ({ page, context }) => {
    await mock(page, { [est]: [ok(await estimate())] });
    await page.goto('/models/maruti-swift');
    await expect(page.getByText('Estimated cash payable')).toBeVisible();
    await context.setOffline(true);
    await expect(page.getByText(/Joining is paused/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Join or start a pool/ })).toBeDisabled();
    await context.setOffline(false);
  });
  test('success', async ({ page }) => {
    await mock(page, { [est]: [ok(await estimate())], 'POST /api/pools': [ok({ created: false, pool: pool() })], 'GET /api/pools/p1$': ['hang'] });
    await page.goto('/models/maruti-swift');
    await page.getByRole('button', { name: /Join or start a pool/ }).click();
    await page.waitForURL(/\/pools\/p1$/);
  });
});

// ---------------------------------------------------------------- pool page
test.describe('pool states', () => {
  const snap = 'GET /api/pools/p1$';
  const stream = 'GET /api/stream/pools/p1';
  test.beforeEach(async ({ page }) => asBuyer(page));
  test('loading', async ({ page }) => {
    await mock(page, { [snap]: ['hang'], [stream]: [{ status: 404 }] });
    await page.goto('/pools/p1');
    await expect(page.getByRole('status', { name: 'Loading the pool' })).toBeVisible();
  });
  test('empty (no one joined yet)', async ({ page }) => {
    await mock(page, { [snap]: [ok(pool({ interested_count: 0, committed_count: 0 }))], [stream]: [{ status: 404 }] });
    await page.goto('/pools/p1');
    await expect(page.getByText(/No one has joined yet/)).toBeVisible();
  });
  test('error', async ({ page }) => {
    await mock(page, { [snap]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'Pools are unavailable right now.')], [stream]: [{ status: 404 }] });
    await page.goto('/pools/p1');
    await expect(page.getByRole('alert').filter({ hasText: 'Pools are unavailable right now.' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
  test('stale', async ({ page }) => {
    await mock(page, { [snap]: [ok(pool()), problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')], [stream]: [ok({})] });
    await page.goto('/pools/p1');
    await expect(page.getByText(/may be out of date/)).toBeVisible();
  });
  test('pending (optimistic join)', async ({ page }) => {
    await mock(page, { [snap]: [ok(pool())], [stream]: [{ status: 404 }], 'POST /api/pools/p1/members': ['hang'] });
    await page.goto('/pools/p1');
    await page.getByRole('button', { name: /Join as interested/ }).click();
    await expect(page.getByText(/Status:/)).toContainText('interested');
  });
  test('conflict (rolled back)', async ({ page }) => {
    await mock(page, { [snap]: [ok(pool({ my_membership: MEMBER }))], [stream]: [{ status: 404 }],
      'DELETE /api/pools/p1/members/me': [problem(409, 'VERSION_CONFLICT', 'Your membership changed; the page now shows the latest state.')] });
    await page.goto('/pools/p1');
    await page.getByRole('button', { name: /Leave this pool/ }).click();
    await expect(page.getByText(/This changed before your action reached us/)).toBeVisible();
    await expect(page.getByText(/Status:/)).toContainText('interested');
  });
  test('offline', async ({ page, context }) => {
    await mock(page, { [snap]: [ok(pool())], [stream]: [{ status: 404 }] });
    await page.goto('/pools/p1');
    await expect(page.getByText(/Who is in/)).toBeVisible();
    await context.setOffline(true);
    await expect(page.getByText(/You are offline/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Join as interested/ })).toBeDisabled();
    await context.setOffline(false);
  });
  test('success', async ({ page }) => {
    await mock(page, { [snap]: [ok(pool()), ok(pool({ interested_count: 5, version: 8, my_membership: MEMBER }))], [stream]: [{ status: 404 }],
      'POST /api/pools/p1/members': [{ status: 201, body: MEMBER }] });
    await page.goto('/pools/p1');
    await page.getByRole('button', { name: /Join as interested/ }).click();
    await expect(page.getByText('You joined as interested.')).toBeVisible();
  });
});
