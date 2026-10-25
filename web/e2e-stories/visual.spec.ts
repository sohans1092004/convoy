import { expect, test } from '@playwright/test';

// Visual regression of the design-system components and interaction states, light and dark, at 360 px and desktop
// (projects in playwright.storybook.config.ts). Baselines (e2e-stories/__screenshots__) are rendered by CI on Linux: a run
// without them writes them and uploads the `visual-baselines` artifact, which is committed. The clock is fixed.
const STORIES = [
  'design-system--buttons', 'design-system--inputs', 'design-system--cards', 'design-system--badges', 'design-system--prices',
  'design-system--countdowns', 'design-system--skeletons', 'design-system--tabs-story', 'design-system--dialog-open', 'design-system--toasts',
  'interaction-states--idle', 'interaction-states--pending', 'interaction-states--error-keeps-input', 'interaction-states--conflict-deadline',
  'interaction-states--conflict-version', 'interaction-states--success', 'interaction-states--price-breakdown',
];

for (const scheme of ['light', 'dark'] as const) {
  for (const story of STORIES) {
    test(`${story} (${scheme})`, async ({ page }) => {
      await page.clock.install({ time: new Date('2026-11-20T06:30:00Z') });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
      await page.goto(`/iframe.html?id=${story}&viewMode=story`);
      await expect(page.locator('#storybook-root > *').first()).toBeAttached(); // some states (idle) render an empty live region
      await page.evaluate(() => document.fonts.ready);
      await expect(page).toHaveScreenshot(`${story}-${scheme}.png`, { fullPage: true, animations: 'disabled' });
    });
  }
}
