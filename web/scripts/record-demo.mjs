// Record the demo video's screen parts against a deployed site (docs/demo-script.md): Playwright video at 1280x720,
// on-screen captions, slides rendered in the same page. Writes <out>/demo.webm and <out>/marks.json (the waits to speed
// up in the edit). Creates one demo scenario on the site (expires in 24 h).
//   node scripts/record-demo.mjs <out-dir>      (WEB_URL defaults to the live site)
import { chromium } from '@playwright/test';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const WEB = process.env.WEB_URL || 'https://convoy-main.duckdns.org';
const OUT = process.argv[2] || 'demo-out';
mkdirSync(OUT, { recursive: true });
const svg = readFileSync(new URL('../../docs/architecture/overview.svg', import.meta.url)).toString('base64');

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: OUT, size: { width: 1280, height: 720 } } });
// The caption lives in sessionStorage so it survives same-origin navigations; slides render it directly.
await ctx.addInitScript(() => {
  const show = () => {
    const text = sessionStorage.getItem('cap');
    if (!text || !document.body) return;
    let el = document.getElementById('demo-cap');
    if (!el) {
      el = document.createElement('div');
      el.id = 'demo-cap';
      el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:rgba(15,23,42,.88);color:#fff;font:600 24px/1.35 system-ui,sans-serif;padding:14px 28px;text-align:center;pointer-events:none';
      document.body.appendChild(el);
    }
    el.textContent = text;
  };
  window.__showCap = show;
  document.addEventListener('DOMContentLoaded', show);
});
const page = await ctx.newPage();
const t0 = Date.now();
const marks = [];
const wait = (ms) => page.waitForTimeout(ms);
const cap = async (text) => { await page.evaluate((t) => { sessionStorage.setItem('cap', t); window.__showCap?.(); }, text).catch(() => {}); };
const fast = async (label, fn) => { const start = (Date.now() - t0) / 1000; await fn(); marks.push({ label, start, end: (Date.now() - t0) / 1000 }); };
const slide = async (html, text, ms) => {
  await page.setContent(`<html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#f6f5f1;font-family:system-ui,sans-serif;color:#1c1917">${html}</body></html>`);
  await page.evaluate((t) => {
    const el = document.createElement('div');
    el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;background:rgba(15,23,42,.88);color:#fff;font:600 24px/1.35 system-ui,sans-serif;padding:14px 28px;text-align:center';
    el.textContent = t; document.body.appendChild(el);
  }, text);
  await wait(ms);
};
const scroll = async (px, ms = 2500) => { await page.mouse.wheel(0, px); await wait(ms); };

// 1. Problem
await slide('<div style="text-align:center;max-width:980px"><div style="font-size:64px;font-weight:800">Convoy</div><div style="font-size:34px;margin-top:18px">Buy the same car together. Dealers compete with sealed, itemised offers.<br><b>The price you confirm can only fall.</b></div><div style="font-size:20px;margin-top:28px;color:#57534e">Live demo · test mode, no real money · synthetic dealers</div></div>',
  'The problem: group buying power, comparable dealer quotes, no surprises after you commit', 14_000);

// 2. Discover and a model page
await page.goto(WEB + '/');
await cap('Discover: cars with checked prices, each price cites its source');
await wait(5000);
await page.goto(WEB + '/models/maruti-swift');
await cap('One price engine (Java on the server, TypeScript in the browser), checked against the same 84 vectors');
await wait(3500);
await scroll(700, 4000);

// 3. Try the demo -> own pool -> join -> commit
await page.goto(WEB + '/');
await cap('Try the demo: a 24-hour sandbox with a buyer, a fresh pool and a demo dealership of your own');
await wait(3000);
await page.getByRole('button', { name: 'Try the demo' }).click();
await page.waitForURL(/\/pools\/[0-9a-f-]+$/, { timeout: 60_000 });
const pool = page.url();
await cap('Your own pool. Joining is free.');
await wait(3000);
await page.getByRole('button', { name: 'Join as interested (free)' }).click();
await page.getByText('You joined as interested.').waitFor();
await cap('Commit with a ₹999 TEST deposit (simulated payment, no real money)');
await wait(2500);
await page.getByRole('checkbox', { name: /I've read these terms/ }).check();
await page.getByRole('button', { name: 'Commit with ₹999 test deposit' }).click();
await page.getByRole('dialog', { name: 'Test payment' }).waitFor();
await wait(2000);
await page.getByRole('button', { name: 'Pay (test)' }).click();
await page.getByText('You are committed.').waitFor({ timeout: 90_000 });
await wait(2500);

// 4. Dealer view: the quote builder with its live preview, submit sealed
await cap('Switch to the dealer side: your demo dealership is invited to quote');
await page.getByRole('button', { name: 'Switch to dealer view' }).click();
await page.waitForURL(/\/dealer/);
const start = page.getByRole('button', { name: 'Start a quote' }).first();
await fast('dealer inbox', () => start.waitFor({ timeout: 120_000 }));
await wait(2000);
await start.click();
await page.waitForURL(/\/dealer\/quotes\//);
await cap('Quote builder: the same engine runs in the browser, so the dealer sees the buyer\'s exact price while typing');
await wait(4000);
await scroll(600, 3500);
await scroll(600, 3500);
await page.getByLabel('Tier 1 discount').fill('10000');
await page.getByLabel('Tier 2 discount').fill('25000');
await page.getByLabel('Units in stock').fill('5');
await page.getByLabel('Colour').fill('White');
await page.getByLabel('Delivery from').fill(new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10));
await cap('Submit sealed: no dealer sees another\'s offer; the round closes on the database clock at commit');
await page.getByRole('button', { name: 'Submit sealed quote' }).click();
await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
await page.getByText('Quote submitted. It is sealed from other dealers.').waitFor();
await wait(3000);

// 5. Buyer view: offers arrive live
await page.getByRole('button', { name: 'Switch to buyer view' }).click();
await page.waitForURL((u) => u.pathname === '/'); // the switch calls the BFF, then sends the browser to /
await page.goto(pool);
await cap('Back as the buyer: offers arrive over a live stream (sped up)');
const compare = page.getByRole('link', { name: 'Compare offers' });
await fast('sealed and final rounds', () => compare.waitFor({ timeout: 6 * 60_000 }));
await wait(2500);

// 6. Compare: itemised offers and the tier ladder
await compare.click();
await page.getByRole('button', { name: 'Accept this offer (not binding)' }).first().waitFor();
await cap('Itemised offers: ex-showroom, road tax, insurance, add-ons, each with its evidence label');
await wait(4500);
await scroll(500, 4000);
await cap('Tiers: the more buyers confirm, the lower the price. You confirm at the ceiling; it can only fall.');
await scroll(500, 5000);
await page.getByRole('button', { name: 'Accept this offer (not binding)' }).first().click();
await page.getByText('Accepted. You can change your mind').waitFor();
await wait(2500);

// 7. Confirm at most the ceiling -> voucher
await cap('My orders: confirm at most the ceiling (sped up while the decision window closes)');
await fast('decision window', async () => {
  for (let i = 0; i < 50; i++) {
    await page.goto(WEB + '/orders');
    if (await page.getByRole('link', { name: /^Order / }).count()) break;
    await wait(5000);
  }
});
await page.getByRole('link', { name: /^Order / }).first().click();
await page.getByRole('button', { name: /Confirm at most/ }).waitFor();
await wait(3000);
await page.getByRole('button', { name: /Confirm at most/ }).click();
await page.getByText(/Confirmed\. Your final price is set/).waitFor();
await cap('An orchestrated saga resolves the tier, holds deposits and issues bookings (sped up)');
await fast('saga to voucher', () => page.getByRole('img', { name: /QR code for booking voucher/ }).waitFor({ timeout: 4 * 60_000 }));
await cap('Booked: final price never above the ceiling, voucher with a signed QR code');
await wait(5000);

// 8. Admin view and the audit chain
await page.goto(WEB + '/demo/admin');
await cap('Admin view (read-only): every figure here is synthetic and labelled simulated');
await wait(5000);
await scroll(600, 4500);
await slide('<div style="max-width:960px;font-size:30px;line-height:1.5"><b>Audit log</b><br>Every event from every service lands in an append-only, hash-chained log.<br>One command verifies the whole chain (<code>verify-chain</code>); every admin read is itself audited.</div>',
  'Audit: append-only, hash-chained, verified by a command', 9000);

// 9. Architecture and three hard problems
await slide(`<img src="data:image/svg+xml;base64,${svg}" style="max-height:600px;max-width:1180px">`,
  'Architecture: a Next.js BFF, a gateway, services with one schema each, SNS/SQS with outbox and inbox', 14_000);
await slide('<div style="max-width:1000px;font-size:30px;line-height:1.6"><b>Three hard problems</b><ol style="margin-top:12px"><li>Sealed bids with deadlines decided by the database clock at commit</li><li>Exactly-once effects over at-least-once messaging: outbox, inbox, six failure tests (MF-1…MF-6)</li><li>An order saga with explicit compensations that recovers from a crash at each of its 4 steps</li></ol></div>',
  'The hard parts, each pinned by tests (laptop + CI)', 16_000);

// 10. Close
await slide('<div style="text-align:center"><div style="font-size:52px;font-weight:800">convoy-main.duckdns.org</div><div style="font-size:24px;margin-top:20px;color:#57534e">Test mode · synthetic dealers · capacity numbers are laptop numbers · full AWS profile parked</div></div>',
  'Try it yourself: press "Try the demo"', 12_000);

const video = page.video();
await ctx.close();
await browser.close();
renameSync(await video.path(), join(OUT, 'demo.webm'));
writeFileSync(join(OUT, 'marks.json'), JSON.stringify(marks, null, 2) + '\n');
console.log(JSON.stringify({ seconds: Math.round((Date.now() - t0) / 1000), marks }, null, 2));
