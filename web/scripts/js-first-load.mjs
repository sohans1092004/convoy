// P7.D: first-load JS per route from the production build alone (no running stack): the gzip size of the root chunks
// plus every entry chunk of the route's layouts, error boundaries and page, as listed in the build's client reference
// manifests. Cross-check: scripts/js-budgets.mjs measures the same thing in a real browser against a running app.
//   pnpm build && node scripts/js-first-load.mjs [--json]
import { readFileSync, existsSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const NEXT = new URL('../.next/', import.meta.url);
const ROUTES = { discover: '/page', model: '/models/[slug]/page', pool: '/pools/[id]/page', compare: '/pools/[id]/compare/page',
  orders: '/orders/page', order: '/orders/[id]/page', saved: '/saved/page', dealer: '/dealer/page', 'sign-in': '/sign-in/page' };
const CAP = { discover: 170, model: 170, pool: 170, compare: 190, orders: 170, order: 170, saved: 170, 'sign-in': 170 }; // buyer routes; dealer has none

const root = JSON.parse(readFileSync(new URL('build-manifest.json', NEXT))).rootMainFiles;
const gz = new Map();
const size = (f) => gz.get(f) ?? gz.set(f, gzipSync(readFileSync(new URL(f, NEXT)), { level: 9 }).length).get(f);

const out = {};
for (const [name, page] of Object.entries(ROUTES)) {
  const file = new URL(`server/app${page}_client-reference-manifest.js`, NEXT);
  if (!existsSync(file)) continue;
  globalThis.__RSC_MANIFEST = {};
  new Function(readFileSync(file, 'utf8'))(); // the manifest is a script that assigns globalThis.__RSC_MANIFEST
  const entries = Object.values(globalThis.__RSC_MANIFEST)[0].entryJSFiles;
  const chunks = new Set(root);
  for (const files of Object.values(entries)) files.forEach((f) => chunks.add(f));
  const kb = [...chunks].reduce((s, f) => s + size(f), 0) / 1024;
  out[name] = { kb: Math.round(kb * 10) / 10, chunks: chunks.size, cap: CAP[name] ?? null, over: CAP[name] ? kb > CAP[name] : null };
}
if (process.argv.includes('--json')) console.log(JSON.stringify(out, null, 2));
else for (const [k, v] of Object.entries(out)) console.log(`${k.padEnd(9)} ${String(v.kb).padStart(6)} KB gzip  ${v.chunks} chunks${v.cap ? `  cap ${v.cap}${v.over ? '  OVER' : ''}` : ''}`);
