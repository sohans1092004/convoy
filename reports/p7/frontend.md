# P7.D frontend: first-load JS per route (production build)

Measured with `web/scripts/js-first-load.mjs`: gzip (level 9) of the root chunks plus every entry chunk of the route's
layouts, error boundaries and page, from the build's client reference manifests. Cross-check against the P2 browser
measurement (`js-budgets.mjs`, 2026-10-25): discover 185.3 vs 185.5 KB, model 189.1 vs 189.4, pool 189.5 vs 189.3.
Laptop build, Next.js 16.4.3 (Turbopack), commit noted per row.

| Route | Before (18bd288) | After (unused Toaster removed) | Cap | Met |
|---|---|---|---|---|
| discover | 185.3 | 174.0 | 170 | no (+4.0) |
| model | 189.1 | 177.8 | 170 | no (+7.8) |
| pool | 189.5 | 178.2 | 170 | no (+8.2) |
| compare | 186.5 | 175.2 | 190 | yes |
| orders | 179.9 | 168.6 | 170 | yes |
| order | 197.6 | 186.3 | 170 | no (+16.3) |
| saved | 184.2 | 172.9 | 170 | no (+2.9) |
| sign-in | 183.6 | 172.3 | 170 | no (+2.3) |
| dealer | 183.7 | 172.4 | none | n/a |

Where the bytes are (discover, after): React + Next runtime (root chunks) ≈ 128 KB, fixed; root layout ≈ 40 KB
(next-intl client runtime with the ICU formatter ≈ 20 KB, TanStack Query ≈ 17 KB, small client components); page ≈ 5 KB.

Fix made: the root layout mounted Radix Toast (`<Toaster>`) on every route, but no component ever shows a toast
(only the Storybook story) → removed from the layout, −11 KB on every route.

Left: discover, model and pool are exactly the routes that use TanStack Query; reaching 170 KB there means replacing it
with a small fetch hook (`use-live-snapshot`, `discover`, `model-estimate`) or moving next-intl formatting to the server.
Both are refactors, not small fixes; not done. LCP (Lighthouse, mobile 4G) needs a running stack: pending.
