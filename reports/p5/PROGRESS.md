# P5 progress

- 02:35Z P5.0 started on p5/ops (from main after merging PR #6 as asked; its last commit had a GatewayIT rate-limit flake, otp 429 vs 200, gateway unchanged in that commit).
- 02:38Z P5.0 written: outbox unique (aggregate_id, aggregate_version) for new rows + 6 order double-version spots fixed; consumer stale_distinct counter/metric, asserted after every service test and in the journey; compose profiles (lean default, ha, tracing); test users only with DEMO; README. Last nightly (pre-merge, P3 main): visual baseline for price-breakdown failed; cancellation journey not in it yet. Next: all service ITs.
- 02:59Z P5.0 DONE: all module tests green (guard caught quote RankChanged reusing quote versions; moved to its own rank stream). Lean stack (no jaeger, no realtime-2) rebuilt; single journey PASS 4.7 min, stale_distinct 0. PR #7 draft.
