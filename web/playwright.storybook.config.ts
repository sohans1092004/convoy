import { defineConfig, devices } from '@playwright/test';

// Storybook checks: every page-state story reaches its state (its play function passes), and visual regression of the
// design-system components, light and dark, at 360 px and desktop. Baselines are generated on Linux (CI fonts).
export default defineConfig({
  testDir: './e2e-stories',
  timeout: 60_000,
  expect: { timeout: 10_000, toHaveScreenshot: { maxDiffPixelRatio: 0.01 } },
  fullyParallel: true,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-stories' }]],
  snapshotPathTemplate: '{testDir}/__screenshots__/{projectName}/{arg}{ext}',
  use: { baseURL: 'http://localhost:6006' },
  webServer: { command: 'node scripts/serve-static.mjs storybook-static 6006', url: 'http://localhost:6006', reuseExistingServer: true },
  projects: [
    { name: 'mobile-360', use: { ...devices['Pixel 5'], viewport: { width: 360, height: 780 } } },
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } } },
  ],
});
