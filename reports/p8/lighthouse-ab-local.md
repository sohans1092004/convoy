# Lighthouse A/B: web 03292c4 vs 2278af4 on one laptop (P8.C, 2026-11-01)

Why: live TBT on 2278af4 measured 535–630 ms at 20:28 and 20:32 UTC (13 min after the deploy) against 194–236 ms on
03292c4 at 19:00 UTC, although 2278af4 ships less JavaScript.

Setup: both published web images (by digest, from their `images` runs) side by side on the laptop (:3001 old, :3002 new)
against the same local backend (docker compose, DEMO data). `@lhci/cli` 0.15.1, mobile preset, simulated slow 4G,
one run per page per round, builds alternating within each round (old, new, old, new...), 3 rounds. Anonymous pages.
Lighthouse CPU benchmark index steady across runs (2,030–2,294).

| Page | 03292c4 TBT (runs) | median | 2278af4 TBT (runs) | median | Performance median old → new |
|---|---|---|---|---|---|
| discover | 455 / 156 / 190 ms | 190 ms | 142 / 104 / 128 ms | 128 ms | 94 → 97 |
| model | 297 / 388 / 137 ms | 297 ms | 147 / 67 / 84 ms | 84 ms | 87 → 96 |

Result: 2278af4 blocks the main thread less than 03292c4; no regression in the code. The top long task in both builds
is the shared framework chunk (`01-*.js`).

Live, single runs with the report kept (2278af4, 20:4x UTC): discover anonymous TBT 214 ms (perf 94); discover with a demo
session TBT 218 ms (perf 95). The official script (`DEMO=1 lighthouse.mjs`, 3 runs per page) at 20:51 UTC: TBT
111 / 85 / 179 ms (discover / model / pool). The high runs at 20:28/20:32 are not reproduced; their cause is not
established (they followed the deploy and the live demo journey within 13 min).
