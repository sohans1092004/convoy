import { expect, test, type FrameLocator, type Page } from '@playwright/test';

// Manual-only: the P1 journey's deposit against REAL Razorpay test mode (not run in CI).
// Requires payment/web started with RAZORPAY_BASE_URL=https://api.razorpay.com and the real checkout.js,
// a webhook registered at <tunnel>/webhooks/razorpay. Pays with Razorpay's test UPI id success@razorpay.
const MAILPIT = 'http://localhost:8025';
const SHOTS = 'test-results/razorpay-real';

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

test.setTimeout(10 * 60_000);

test('deposit captured by real Razorpay test mode', async ({ page }) => {
  const t0 = Date.now();
  const log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1)}s ${s}`);
  await signIn(page, process.env.BUYER ?? 'buyer1@convoy.test');
  await page.goto('/models/maruti-swift');
  await page.getByRole('radio', { name: /LXi MT/i }).check();
  await page.getByRole('button', { name: /Join or start a pool in Pune/ }).click();
  await page.waitForURL(/\/pools\/[0-9a-f-]+$/);
  log(`pool ${page.url().split('/').pop()}`);
  await page.getByRole('checkbox', { name: /I've read these terms/ }).check();
  await page.getByRole('button', { name: 'Commit with ₹999 test deposit' }).click();

  const frame: FrameLocator = page.frameLocator('iframe.razorpay-checkout-frame');
  await expect(frame.locator('body')).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: `${SHOTS}/1-checkout.png` });
  log('checkout open');

  // Contact overlay (shown when no prefill): any test phone number.
  const phone = frame.getByPlaceholder('Mobile number');
  if (await phone.isVisible().catch(() => false)) {
    await phone.fill('9123456789');
    await phone.press('Enter');
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${SHOTS}/2-after-contact.png` });
    await page.waitForTimeout(2000);
  }
  await page.screenshot({ path: `${SHOTS}/3-methods.png` });
  // Netbanking test flow: pick a bank, Pay opens Razorpay's test bank page with Success / Failure.
  await frame.getByText('Netbanking', { exact: true }).first().click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${SHOTS}/4-netbanking.png` });
  const popupPromise = page.context().waitForEvent('page', { timeout: 60_000 });
  await frame.getByText('Canara Bank', { exact: true }).first().click();
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${SHOTS}/5-bank.png` });
  const pay = frame.getByRole('button', { name: /^pay/i }).last();
  if (await pay.isVisible().catch(() => false)) await pay.click();
  const bank = await popupPromise;
  await bank.waitForLoadState();
  await bank.screenshot({ path: `${SHOTS}/6-testbank.png` });
  await bank.getByRole('button', { name: /success/i }).first().click();
  log('test bank: Success clicked');

  await expect(page.getByText('You are committed.')).toBeVisible({ timeout: 180_000 });
  await page.screenshot({ path: `${SHOTS}/7-committed.png` });
  log('committed: payment.captured webhook arrived through the tunnel');

  // Refund: leaving before confirmation is free and refunded in full.
  await page.getByRole('button', { name: /Leave this pool/ }).click();
  await expect(page.getByText('You left the pool.')).toBeVisible({ timeout: 30_000 });
  log('left the pool: refund requested');
});
