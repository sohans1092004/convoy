import { expect, test } from '@playwright/test';

// P5.B: installable manifest + icons; the service worker serves the offline page and caches nothing else.
test('manifest is installable', async ({ page, request }) => {
  const m = await (await request.get('/manifest.webmanifest')).json();
  expect(m).toMatchObject({ name: 'Convoy', start_url: '/', display: 'standalone' });
  for (const size of ['192x192', '512x512']) {
    const icon = m.icons.find((i: { sizes: string; purpose: string }) => i.sizes === size && i.purpose === 'any');
    const res = await request.get(icon.src);
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toBe('image/png');
  }
  await page.goto('/');
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.webmanifest');
});

test('offline navigation shows the offline page and only it is cached', async ({ page, context }) => {
  await page.goto('/');
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await page.reload(); // now controlled by the worker
  await context.setOffline(true);
  await page.goto('/notifications');
  await expect(page.getByRole('heading', { name: 'You are offline' })).toBeVisible();
  const cached = await page.evaluate(async () => {
    const urls: string[] = [];
    for (const k of await caches.keys()) for (const r of await (await caches.open(k)).keys()) urls.push(new URL(r.url).pathname);
    return urls;
  });
  expect(cached).toEqual(['/offline.html']);
  await context.setOffline(false);
});
