// Lighthouse CI on the discover, model and pool pages (frontend-quality.md §2): mobile preset (emulated phone, simulated
// slow 4G), 3 runs each, median reported. Records LCP, CLS, TBT and the performance score in
// ../reports/p2/lighthouse.json. A real test session is created first (email OTP via Mailpit) so the pool page renders
// its live content, and its cookie is passed to Lighthouse.
//   node scripts/lighthouse.mjs        (stack + production web app running; WEB_URL, GATEWAY_URL, MAILPIT_URL)
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const WEB = process.env.WEB_URL || 'http://localhost:3000';
const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const RUNS = Number(process.env.LH_RUNS || 3);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, { session, body, idem } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (session) headers['X-Convoy-Session'] = session;
  if (idem) headers['Idempotency-Key'] = idem;
  for (let attempt = 1; ; attempt++) {
    const r = await fetch(GW + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    if (r.ok) return r.json();
    if (r.status !== 503 || attempt === 4) throw new Error(`${method} ${path} -> ${r.status} ${await r.text()}`);
    await sleep(2000 * attempt); // a busy laptop stack: same Idempotency-Key, so a retry is safe
  }
}

async function signIn(email) {
  const before = Date.now();
  const r = await fetch(`${GW}/api/auth/otp/start`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email }) });
  if (r.status !== 202) throw new Error(`otp start ${r.status}`);
  for (let i = 0; i < 60; i++) {
    const m = (await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}&limit=1`)).json()).messages?.[0];
    const code = m && Date.parse(m.Created) >= before - 2000 ? m.Snippet.match(/\b(\d{6})\b/)?.[1] : null;
    if (code) return (await api('POST', '/api/auth/otp/verify', { body: { email, code } })).session_id;
    await sleep(500);
  }
  throw new Error('no OTP mail');
}

const session = await signIn(`lighthouse-${Date.now().toString(36)}@convoy.test`);
const month = new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + 2, 1)).toISOString().slice(0, 7);
const pool = (await api('POST', '/api/pools', { session, idem: randomUUID(), body: { model_slug: 'maruti-swift', city: 'Mumbai', purchase_month: month } })).pool.id;
await api('POST', `/api/pools/${pool}/members`, { session, idem: randomUUID() }).catch(() => {});

const pages = { discover: `${WEB}/`, model: `${WEB}/models/maruti-swift`, pool: `${WEB}/pools/${pool}` };
const out = join(process.cwd(), '.lighthouseci');
rmSync(out, { recursive: true, force: true });
const chrome = chromium.executablePath();
const results = {};
for (const [name, url] of Object.entries(pages)) {
  execFileSync(process.execPath, [join('node_modules', '@lhci', 'cli', 'src', 'cli.js'), 'collect', `--url=${url}`, `--numberOfRuns=${RUNS}`,
    `--settings.chromeFlags=--headless=new --no-sandbox`, `--settings.extraHeaders=${JSON.stringify({ Cookie: `cv_sid=${session}; cv_roles=buyer` })}`],
  { stdio: 'inherit', env: { ...process.env, CHROME_PATH: chrome } });
  const lhrs = readdirSync(out).filter((f) => f.startsWith('lhr-') && f.endsWith('.json')).map((f) => JSON.parse(readFileSync(join(out, f), 'utf8')))
    .filter((l) => l.finalDisplayedUrl?.startsWith(url) || l.requestedUrl === url);
  const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];
  results[name] = {
    url: url.replace(pool, '<pool-id>'), runs: lhrs.length,
    form_factor: lhrs[0]?.configSettings.formFactor, throttling: lhrs[0]?.configSettings.throttlingMethod,
    lcp_ms: Math.round(median(lhrs.map((l) => l.audits['largest-contentful-paint'].numericValue))),
    cls: +median(lhrs.map((l) => l.audits['cumulative-layout-shift'].numericValue)).toFixed(3),
    tbt_ms: Math.round(median(lhrs.map((l) => l.audits['total-blocking-time'].numericValue))),
    performance: Math.round(median(lhrs.map((l) => l.categories.performance.score)) * 100),
  };
  rmSync(out, { recursive: true, force: true });
}
const report = { measured_at: new Date().toISOString(), commit: execFileSync('git', ['rev-parse', '--short', 'HEAD']).toString().trim(), lighthouse_cli: '@lhci/cli 0.15.1', results };
writeFileSync(join(process.cwd(), '..', 'reports', 'p2', 'lighthouse.json'), JSON.stringify(report, null, 2) + '\n');
console.table(Object.fromEntries(Object.entries(results).map(([k, v]) => [k, { LCP_ms: v.lcp_ms, CLS: v.cls, TBT_ms: v.tbt_ms, perf: v.performance }])));
