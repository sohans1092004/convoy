import { expect, test } from '@playwright/test';

// Passkeys end to end (P2 scope F) with Chromium's virtual authenticator (CDP WebAuthn domain): register after an
// email-code sign-in, sign out, sign back in with the passkey alone. The server side is identity E003-E005/E151.
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';

test('a buyer adds a passkey and signs in with it, no email code', async ({ page }) => {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });

  const email = `passkey-${Date.now().toString(36)}-${test.info().project.name}@convoy.test`;
  await page.goto('/sign-in');
  const before = Date.now();
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Email me a code' }).click();
  let code = '';
  await expect.poll(async () => {
    const m = (await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json()).messages?.[0];
    if (m && Date.parse(m.Created) >= before - 2000) code = m.Snippet.match(/\b(\d{6})\b/)?.[1] ?? '';
    return code;
  }, { timeout: 30_000 }).toMatch(/^\d{6}$/);
  await page.getByLabel('6-digit code').fill(code);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/sign-in'));

  await page.goto('/account');
  await page.getByRole('button', { name: 'Add a passkey' }).click();
  await expect(page.getByText('Passkey added.')).toBeVisible();
  const { credentials } = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
  expect(credentials).toHaveLength(1);
  expect(credentials[0].isResidentCredential).toBe(true);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();

  await page.goto('/sign-in');
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/sign-in'));
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  expect((await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials[0].signCount).toBeGreaterThan(0);

  // A failed prompt (here: the user does not pass the fingerprint/face check) is explained, not shown as a network error.
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible(); // sign-out's own navigation must finish first
  await cdp.send('WebAuthn.setUserVerified', { authenticatorId, isUserVerified: false });
  await page.goto('/sign-in');
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(page.getByText(/passkey prompt was cancelled or this device has no Convoy passkey/)).toBeVisible({ timeout: 15_000 });
});
