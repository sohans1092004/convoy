import { expect, test } from '@playwright/test';

// The full AWS profile serves a self-signed certificate (no CloudFront, no domain: infra/aws/full/edge.tf). Only there,
// FULL_SELF_SIGNED=1 skips TLS verification; the lite host (the public demo) has a real certificate and keeps it on.
test.use({ ignoreHTTPSErrors: process.env.FULL_SELF_SIGNED === '1' });

// P6.B/P6.D: the public demo as a stranger meets it, against any deployment (WEB_URL), no email and no test accounts:
// Try the demo -> own pool -> join + simulated deposit -> the simulated dealers quote -> compare -> accept -> confirm ->
// booking voucher; then the dealer view of the visitor's own demo dealership and the read-only admin view.
//   WEB_URL=https://convoy-demo.duckdns.org pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360
test('public demo: a stranger books through simulated dealers', async ({ page }, info) => {
  test.setTimeout(15 * 60_000);
  const t0 = Date.now();
  const step = (s: string) => console.log(`[${info.project.name}] ${((Date.now() - t0) / 1000).toFixed(1)}s ${s}`);

  await page.goto('/');
  await expect(page.getByText('Demo: synthetic dealers, test payments, no real money')).toBeVisible();
  await page.getByRole('button', { name: 'Try the demo' }).click();
  await page.waitForURL(/\/pools\/[0-9a-f-]+$/, { timeout: 60_000 });
  step(`scenario created, own pool ${page.url().split('/').pop()}`);

  await page.getByRole('button', { name: 'Join as interested (free)' }).click();
  await expect(page.getByText('You joined as interested.')).toBeVisible();
  await page.getByRole('checkbox', { name: /I've read these terms/ }).check();
  await page.getByRole('button', { name: 'Commit with ₹999 test deposit' }).click();
  await expect(page.getByRole('dialog', { name: 'Test payment' })).toBeVisible();
  await page.getByRole('button', { name: 'Pay (test)' }).click();
  await expect(page.getByText('You are committed.')).toBeVisible({ timeout: 90_000 });
  step('committed with the simulated payment');

  // Sealed round (90 s), the simulated dealers quote, final round (20 s); the compare link appears when quotes are FINAL.
  await expect(page.getByRole('link', { name: 'Compare offers' })).toBeVisible({ timeout: 6 * 60_000 });
  await page.getByRole('link', { name: 'Compare offers' }).click();
  const offers = page.getByRole('button', { name: 'Accept this offer (not binding)' });
  await expect(offers.first()).toBeVisible();
  step(`${await offers.count()} offer(s) from simulated dealers`);
  await offers.first().click();
  await expect(page.getByText('Accepted. You can change your mind')).toBeVisible();

  await expect.poll(async () => {
    await page.goto('/orders');
    return page.getByRole('link', { name: /^Order / }).count();
  }, { timeout: 4 * 60_000, intervals: [5000] }).toBeGreaterThan(0);
  await page.getByRole('link', { name: /^Order / }).first().click();
  await page.getByRole('button', { name: /Confirm at most/ }).click();
  await expect(page.getByText(/Confirmed\. Your final price is set/)).toBeVisible();
  await expect(page.getByRole('img', { name: /QR code for booking voucher/ })).toBeVisible({ timeout: 4 * 60_000 });
  step('booked; voucher shown');

  await page.getByRole('button', { name: 'Switch to dealer view' }).click();
  await page.waitForURL(/\/dealer/);
  await expect(page.getByRole('heading').first()).toBeVisible();
  step('dealer view of the visitor\'s own demo dealership');

  await page.goto('/demo/admin');
  await expect(page.getByRole('heading', { name: 'Admin view (read-only, synthetic data)' })).toBeVisible();
  step('read-only admin view');
});
