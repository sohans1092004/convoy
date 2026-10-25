import { defineConfig, devices } from '@playwright/test';

// Manual-only run against real Razorpay test mode (see e2e-real/). Never part of CI.
export default defineConfig({
  testDir: './e2e-real',
  workers: 1,
  reporter: [['list']],
  use: { baseURL: process.env.WEB_URL || 'http://localhost:3000', ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 },
    actionTimeout: 30_000, navigationTimeout: 30_000, trace: 'on', video: 'on' },
});
