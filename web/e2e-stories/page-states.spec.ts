import { expect, test } from '@playwright/test';

// PLAN §5.2: each page-state story must render and pass its play function.
const STORIES = [
  'discover-loading',
  'discover-empty',
  'discover-error-keeps-search',
  'discover-stale',
  'discover-pending',
  'discover-conflict',
  'discover-offline',
  'discover-success',
  'join-loading',
  'join-empty',
  'join-error-keeps-choices',
  'join-stale',
  'join-pending',
  'join-conflict',
  'join-offline',
  'join-success',
  'pool-loading',
  'pool-empty',
  'pool-error',
  'pool-stale',
  'pool-pending',
  'pool-conflict',
  'pool-offline',
  'pool-success',
];

test('the story file has eight states for each of the three pages', async ({ request }) => {
  const ids = Object.keys(((await (await request.get('/index.json')).json()) as { entries: Record<string, unknown> }).entries).filter((id) => id.startsWith('page-states--'));
  expect(ids.sort()).toEqual(STORIES.map((s) => `page-states--${s}`).sort());
});

for (const story of STORIES) {
  test(`state story ${story}`, async ({ page }) => {
    // A failed play function logs its error to the console; Storybook 10 shows no error display for it (P8.C: every
    // story passed this test while rendering its error state, because the API mock never reached the client).
    const errors: string[] = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().split('\n')[0]); });
    await page.goto(`/iframe.html?id=page-states--${story}&viewMode=story`);
    await expect(page.locator('#storybook-root > *').first()).toBeVisible({ timeout: 15_000 });
    await page.waitForFunction(() => !document.body.classList.contains('sb-show-preparing-story'), null, { timeout: 15_000 });
    await page.waitForTimeout(2000); // the play function finishes
    const error = page.locator('body.sb-show-errordisplay');
    expect(await error.count(), await page.locator('#error-message').innerText().catch(() => '')).toBe(0);
    expect(errors, 'console errors (a failed play function)').toEqual([]);
  });
}

// P8.C: a non-member must not open the members-only pool stream (it answered 404 and looped on "Reconnecting…").
// Checked here directly, not only in the story's play function.
test('pool page: a non-member opens no stream and sees the join hint', async ({ page }) => {
  await page.goto('/iframe.html?id=page-states--pool-empty&viewMode=story');
  await expect(page.getByText('Join to see live updates')).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(1000);
  expect(await page.evaluate(() => (window as unknown as { __streamsOpened?: number }).__streamsOpened)).toBe(0);
  await expect(page.getByText(/Reconnecting/)).toHaveCount(0);
});
