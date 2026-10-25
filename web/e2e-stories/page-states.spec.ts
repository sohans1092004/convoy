import { expect, test } from '@playwright/test';

// PLAN §5.2: each page-state story must render and pass its play function (Storybook shows .sb-errordisplay on failure).
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
    await page.goto(`/iframe.html?id=page-states--${story}&viewMode=story`);
    await expect(page.locator('#storybook-root > *').first()).toBeVisible({ timeout: 15_000 });
    await page.waitForFunction(() => !document.body.classList.contains('sb-show-preparing-story'), null, { timeout: 15_000 });
    await page.waitForTimeout(2000); // the play function finishes
    const error = page.locator('body.sb-show-errordisplay');
    expect(await error.count(), await page.locator('#error-message').innerText().catch(() => '')).toBe(0);
  });
}
