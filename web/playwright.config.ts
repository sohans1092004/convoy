import { defineConfig, devices } from '@playwright/test';

// Full journey against `docker compose up` (demo durations). Two widths run in parallel on separate
// pools (different models), so their deadlines never interfere.
export default defineConfig({
  testDir: './e2e',
  timeout: 15 * 60_000,
  expect: { timeout: 20_000 },
  fullyParallel: true,
  workers: 2,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: process.env.WEB_URL || 'http://localhost:3000',
    actionTimeout: 30_000,
    trace: 'retain-on-failure',
    video: process.env.RECORD_VIDEO ? 'on' : 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'mobile-360', use: { ...devices['Pixel 5'], viewport: { width: 360, height: 780 } } },
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } } },
  ],
});
