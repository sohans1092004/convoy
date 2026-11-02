# Lighthouse A/B: web be3d55d vs d5e1f8b on one laptop (ship pass, 2026-11-02)

Why: live LCP on 7c48456 measured 3.3 to 8.9 s against a 2.5 s gate, while the laptop's link to the host ran at 2 to 30 KB/s. This run takes the link away, to see what the code itself costs.

Setup: both production builds side by side on the laptop (:3001 old, :3002 new). `@lhci/cli` 0.15.1, mobile preset, simulated slow 4G, one run per round, builds alternating, 3 rounds. Discover page only, with no backend, so the catalogue shows its error state. Use this to compare the builds, not as an absolute figure.

## As shipped: Plex Sans preloaded

| Build | LCP (runs) | Median | FCP median | Weight | Performance |
|---|---|---|---|---|---|
| be3d55d | 2,906 / 2,713 / 2,845 ms | 2,845 ms | 996 ms | 193 KB | 83 to 88 |
| d5e1f8b | 3,104 / 3,195 / 3,055 ms | 3,104 ms | 979 ms | 236 KB | 82 to 85 |

The new build costs about 260 ms of LCP and 43 KB. The LCP element changed from the demo intro to the home lede, which is now larger.

## Tried and not kept: no font preload

| Build | LCP (runs) | Median | FCP median | Weight | Performance |
|---|---|---|---|---|---|
| be3d55d | 2,638 / 2,610 / 2,599 ms | 2,610 ms | 917 ms | 195 KB | 95 to 96 |
| no preload | 2,756 / 2,737 / 2,771 ms | 2,756 ms | 1,369 ms | 236 KB | 94 to 95 |

The LCP gap fell to about 146 ms, but FCP became about 450 ms slower. The laptop was also quieter in this round, as the old build's own figures show, so the two tables can't be compared directly. The preload stayed.

## What this does and does not show

- The gap of 4 s or more on the live site is far larger than the 260 ms this code adds here, so most of it is not this code. The link is the likely cause, but that isn't proven: no run has been made from another network.
- 2.5 s is not shown to be met on the live site by this build. It needs a re-run when the link recovers.
