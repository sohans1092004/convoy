// Route-level initial JS (frontend-quality.md §3): the gzip-compressed size of every JS chunk a route loads on first
// visit to the production build. Measured in a real browser (the chunks the page actually requests) and gzipped here,
// so the number does not depend on server compression settings. Budget = baseline x 1.10 (P2 prompt, Q-037); the
// starting caps of frontend-quality.md §3 are kept alongside and routes over them are flagged for the report.
//   node scripts/js-budgets.mjs --record   measure and write perf/budgets.json (baseline + budget)
//   node scripts/js-budgets.mjs            measure and fail if any route exceeds its budget (CI)
// Needs the production web app at WEB_URL (default http://localhost:3000) and the catalogue behind it.
import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const WEB = process.env.WEB_URL || 'http://localhost:3000';
const FILE = new URL('../perf/budgets.json', import.meta.url);
const KB = 1024;
// Caps from frontend-quality.md §3 (starting assumptions, Q-037). Routes without a named cap use the largest (230 KB).
const ROUTES = [
  { name: 'discover', path: '/', cap: 170 },
  { name: 'join (model page)', path: '/models/maruti-swift', cap: 150 },
  { name: 'pool', path: '/pools/p1', cap: 170 },
  { name: 'compare', path: '/pools/p1/compare', cap: 190 },
  { name: 'confirm (order)', path: '/orders/o1', cap: 150 },
  { name: 'my cars', path: '/saved', cap: 170 },
  { name: 'quote builder', path: '/dealer/quotes/q1', cap: 230, roles: 'dealer_staff' },
];

async function measure(browser, route) {
  const ctx = await browser.newContext();
  const host = new URL(WEB).hostname;
  await ctx.addCookies([{ name: 'cv_sid', value: 'budget-run', domain: host, path: '/' }, { name: 'cv_roles', value: route.roles ?? 'buyer', domain: host, path: '/' }]);
  const page = await ctx.newPage();
  // Data does not change which chunks load: answer every API call with an empty 503 (the page shows its error state).
  await page.route('**/api/**', (r) => r.fulfill({ status: 503, contentType: 'application/problem+json', body: '{"status":503}' }));
  const scripts = new Map();
  page.on('response', async (res) => {
    const u = res.url();
    if (res.request().resourceType() === 'script' && u.startsWith(WEB)) {
      try { scripts.set(u, gzipSync(await res.body(), { level: 9 }).length); } catch { /* aborted */ }
    }
  });
  await page.goto(WEB + route.path, { waitUntil: 'networkidle' });
  await ctx.close();
  return { bytes: [...scripts.values()].reduce((a, b) => a + b, 0), chunks: scripts.size };
}

const browser = await chromium.launch();
const results = [];
for (const r of ROUTES) results.push({ ...r, ...(await measure(browser, r)) });
await browser.close();

const record = process.argv.includes('--record');
const saved = record ? {} : JSON.parse(readFileSync(FILE, 'utf8')).routes;
let failed = false;
const rows = results.map((r) => {
  const kb = +(r.bytes / KB).toFixed(1);
  if (record) {
    saved[r.path] = { route: r.name, baseline_kb: kb, budget_kb: +(kb * 1.1).toFixed(1), starting_cap_kb: r.cap, over_starting_cap: kb > r.cap };
    return `${r.name.padEnd(20)} ${String(kb).padStart(7)} KB gzip  (${r.chunks} chunks)  budget ${saved[r.path].budget_kb} KB${kb > r.cap ? `  (over the ${r.cap} KB starting cap)` : ''}`;
  }
  const b = saved[r.path];
  const over = !b || kb > b.budget_kb;
  failed ||= over;
  return `${over ? 'OVER' : 'ok  '} ${r.name.padEnd(20)} ${String(kb).padStart(7)} KB gzip  budget ${b?.budget_kb ?? '?'} KB  (baseline ${b?.baseline_kb ?? '?'})`;
});
console.log(rows.join('\n'));
if (record) {
  writeFileSync(FILE, JSON.stringify({ _note: 'gzip KB of first-load JS per route; budget = baseline x 1.10 (P2, Q-037); starting caps from frontend-quality.md §3 kept for reference. Raising a budget needs a PR note.', measured: new Date().toISOString().slice(0, 10), routes: saved }, null, 2) + '\n');
  console.log(`wrote ${FILE.pathname}`);
}
if (failed) process.exit(1);
