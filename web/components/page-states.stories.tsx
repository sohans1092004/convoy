import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, userEvent, within } from 'storybook/test';
import { Discover } from './discover';
import { ModelEstimate } from './model-estimate';
import { PoolView } from './pool-view';
import { ESTIMATE, MEMBER, MODELS, Mocked, VARIANTS, pool, problem, type Routes } from './story-mocks';

/**
 * PLAN §5.2: the eight states (loading, empty, error, stale, pending, conflict, offline, success) on the discover, join
 * and pool pages. Each story renders the real component against mocked /api responses; e2e/states.spec.ts checks the
 * same states in the running app.
 */
const meta: Meta = { title: 'Page states', parameters: { layout: 'padded', nextjs: { appDirectory: true } } };
export default meta;
type Story = StoryObj;

const ok = (body: unknown) => ({ status: 200, body });
const models = (routes: Routes, offline = false) => () => <Mocked routes={routes} offline={offline}><Discover /></Mocked>;
const join = (routes: Routes, offline = false) => () => (
  <Mocked routes={routes} offline={offline}><ModelEstimate slug="maruti-swift" variants={VARIANTS} canJoin signedIn /></Mocked>
);
const poolPage = (routes: Routes, offline = false, stream: 'live' | 'down' = 'down') => () => (
  <Mocked routes={routes} offline={offline} stream={stream}><PoolView poolId="p1" checkoutUrl="/checkout.js" /></Mocked>
);

// ---------------------------------------------------------------- discover
export const DiscoverLoading: Story = { render: models({ 'GET /api/catalog/models': ['hang'] }) };
export const DiscoverEmpty: Story = {
  render: models({ 'GET /api/catalog/models': [ok(MODELS)] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.type(await c.findByLabelText('Search cars'), 'zzz');
    await expect(await c.findByText(/No cars match/)).toBeVisible();
  },
};
export const DiscoverErrorKeepsSearch: Story = {
  render: models({ 'GET /api/catalog/models': [problem(503, 'DEPENDENCY_UNAVAILABLE', 'Catalogue unavailable')] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.type(await c.findByLabelText('Search cars'), 'creta');
    await expect(await c.findByRole('alert')).toHaveTextContent(/search is kept/);
    await expect(c.getByLabelText('Search cars')).toHaveValue('creta');
  },
};
export const DiscoverStale: Story = {
  render: models({ 'GET /api/catalog/models\\?city=Pune': [problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')], 'GET /api/catalog/models': [ok(MODELS)] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await c.findByText('Creta', { exact: false });
    await userEvent.selectOptions(c.getByLabelText('City'), 'Pune');
    await expect(await c.findByText(/latest refresh failed/)).toBeVisible();
  },
};
export const DiscoverPending: Story = {
  render: models({ 'GET /api/catalog/models\\?city=Pune': ['hang'], 'GET /api/catalog/models': [ok(MODELS)] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await c.findByText('Creta', { exact: false });
    await userEvent.selectOptions(c.getByLabelText('City'), 'Pune');
    await expect(await c.findByText(/Updating prices for Pune/)).toBeVisible();
  },
};
export const DiscoverConflict: Story = {
  name: 'Discover conflict (prices recalculated while browsing)',
  render: models({ 'GET /api/catalog/models\\?city=Pune': [ok({ ...MODELS, ruleset_version: '2026.11.01-p1' })], 'GET /api/catalog/models': [ok(MODELS)] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await c.findByText('Creta', { exact: false });
    await userEvent.selectOptions(c.getByLabelText('City'), 'Pune');
    await expect(await c.findByText(/recalculated while you browsed/)).toBeVisible();
  },
};
export const DiscoverOffline: Story = { render: models({ 'GET /api/catalog/models': [ok(MODELS)] }, true) };
export const DiscoverSuccess: Story = {
  render: models({ 'GET /api/catalog/models': [ok(MODELS)] }),
  play: async ({ canvasElement }) => { await expect(await within(canvasElement).findByText(/3 cars/)).toBeVisible(); },
};

// ---------------------------------------------------------------- join (model page)
const est = 'GET /api/catalog/variants/[^/]+/price-estimate';
export const JoinLoading: Story = { render: join({ [est]: ['hang'] }) };
export const JoinEmpty: Story = { name: 'Join empty (no verified price here)', render: join({ [est]: [problem(422, 'QUOTE_INVALID', 'RTO_UNVERIFIED')] }) };
export const JoinErrorKeepsChoices: Story = { render: join({ [est]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] }) };
export const JoinStale: Story = {
  render: join({ [`${est}\\?city=Delhi`]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')], [est]: [ok(ESTIMATE)] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await c.findByText('Estimated cash payable');
    await userEvent.selectOptions(c.getByLabelText('City'), 'Delhi');
    await expect(await c.findByText(/could not be refreshed/)).toBeVisible();
  },
};
export const JoinPending: Story = {
  render: join({ [est]: [ok(ESTIMATE)], 'POST /api/pools': ['hang'] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Join or start a pool/ }));
    await expect(c.getByRole('button', { name: /Join or start a pool/ })).toHaveAttribute('aria-busy', 'true');
  },
};
export const JoinConflict: Story = {
  render: join({ [est]: [ok(ESTIMATE)], 'POST /api/pools': [problem(409, 'ACTIVE_COMMITMENT_EXISTS', 'You already hold a commitment for this model in another pool.')] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Join or start a pool/ }));
    await expect(await c.findByText(/already hold a commitment/)).toBeVisible();
  },
};
export const JoinOffline: Story = { render: join({ [est]: [ok(ESTIMATE)] }, true) };
export const JoinSuccess: Story = {
  render: join({ [est]: [ok(ESTIMATE)], 'POST /api/pools': [ok({ created: false, pool: pool() })] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Join or start a pool/ }));
    await expect(await c.findByText(/Pool ready/)).toBeVisible();
  },
};

// ---------------------------------------------------------------- pool page
const snap = 'GET /api/pools/p1$';
export const PoolLoading: Story = { render: poolPage({ [snap]: ['hang'] }) };
export const PoolEmpty: Story = { name: 'Pool empty (no one joined yet)', render: poolPage({ [snap]: [ok(pool({ interested_count: 0, committed_count: 0 }))] }) };
export const PoolError: Story = { render: poolPage({ [snap]: [problem(503, 'DEPENDENCY_UNAVAILABLE', 'Pools are unavailable right now.')] }) };
export const PoolStale: Story = {
  render: poolPage({ [snap]: [ok(pool()), problem(503, 'DEPENDENCY_UNAVAILABLE', 'down')] }, false, 'live'),
  play: async ({ canvasElement }) => { await expect(await within(canvasElement).findByText(/may be out of date/, {}, { timeout: 5000 })).toBeVisible(); },
};
export const PoolPending: Story = {
  render: poolPage({ [snap]: [ok(pool())], 'POST /api/pools/p1/members': ['hang'] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Join as interested/ }));
    await expect(await c.findByText(/Status:/)).toHaveTextContent(/interested/); // optimistic
  },
};
export const PoolConflict: Story = {
  render: poolPage({ [snap]: [ok(pool({ my_membership: MEMBER }))], 'DELETE /api/pools/p1/members/me': [problem(409, 'VERSION_CONFLICT', 'Your membership changed; the page now shows the latest state.')] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Leave this pool/ }));
    await expect(await c.findByText(/This changed before your action reached us/)).toBeVisible();
  },
};
export const PoolOffline: Story = { render: poolPage({ [snap]: [ok(pool())] }, true) };
export const PoolSuccess: Story = {
  render: poolPage({ [snap]: [ok(pool()), ok(pool({ interested_count: 5, version: 8, my_membership: MEMBER }))], 'POST /api/pools/p1/members': [{ status: 201, body: MEMBER }] }),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(await c.findByRole('button', { name: /Join as interested/ }));
    await expect(await c.findByText('You joined as interested.')).toBeVisible();
  },
};
