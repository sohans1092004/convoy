# Phase 3 report: dealers and quotes

Branch `p3/dealers-quotes`, PR #5 (now based on `main` after PRs #3 and #4 were merged). Date 2026-10-25. All local numbers
come from one laptop (Docker Desktop, WSL 2). Progress log: `reports/p3/PROGRESS.md`.

## 1. Summary
Dealers can now register (GSTIN checked), be verified by a passkey admin, invite staff, and quote through a full builder:
sealed then final rounds, immutable versions with audited diffs, withdrawal and post-FINAL cancellation, ranking at the
ceiling price with only each dealer's own rank and of_n visible. Buyers compare offers on mobile-first cards with evidence
labels, a labelled projection, stock confidence, a flag for a dealer-required add-on others do not require, and a live
quote count that never carries quote contents. A deterministic dealer simulator drives three synthetic dealerships through
the real APIs; the nightly workflow runs a three-dealer journey. Every P3 subtask landed with its integration tests, the
P1 journey passed after each subtask, and the first nightly run was fully green.

## 2. What was built (P3.A–P3.F)
- **P3.A Onboarding.** E050 registration (GSTIN shape, state code 01–38, mod-36 check character; starts
  `PENDING_VERIFICATION`), E153 admin queue, E053 approve/reject (reason required, If-Match, audited in
  `dealership_audit`). Approval emits `DealershipVerified` (with `registered_by`): identity makes the registrant the admin;
  only VERIFIED dealerships are eligible for rounds (E055). Staff invites E010/E011 (token hashed, 72 h, one use, bound to
  the invited email). ESLint 9 + `eslint-config-next` as a required CI check (zero warnings).
- **P3.B Quote lifecycle.** E064 revise (new immutable version, fresh price snapshots, field diff in the audit trail),
  E065 withdraw until the round deadline, E066 admin cancel after FINAL (dealer cancellation, `QuoteWithdrawn{after_final}`).
  `quote_versions` immutable by trigger. Validity date and stock checks; stock claims stamped `DEALER_CONFIRMED`.
- **P3.C Rounds, ranking, deadlines.** Sealed then final round with durations from config. Ranking per ranking-rule.md
  (engine RANKING mode at the ceiling tier; ties: delivery date, committed_at, quote id); rank snapshots computed hidden at
  the sealed close and revealed in the same transaction that opens the final round; `RankChanged` on every move. E068 own
  rank `{variant_id, rank, of_n, rule_version, computed_at}` only. Dealer stream E121. Jaeger in-memory store capped.
- **P3.D Quote builder UI.** Live engine preview, validation that blocks submit, mandatory/optional accessory tables,
  baseline insurance with the binding explanation, tier editor with a what-if 3/7/12 table and the price-only-falls rule,
  validity and deadline countdowns, confirm dialog, immutable submitted view, revision change list, rank badge with the rule.
- **P3.E Buyer compare.** Stacked 360 px breakdown (no sideways scroll) with evidence labels on every line; tier ladder,
  ceiling + projection, stock confidence, last updated; buyer stream E122 (member-only quote count and a comparison-ready
  signal, never contents); 78 hard-coded strings moved into next-intl (Hindi machine-drafted). Journey failure capture.
- **P3.F Simulator and three-dealer journey.** `tools/simulator` (seeded, API-only; aggressive, conservative,
  slow responder, withdraws, mispriced add-on; docs/product/dealer-simulator.md); three synthetic verified dealerships in
  Pune; `THREE_DEALERS=1` journey (nightly); add-on flag on the compare screen; stock wording translated.

## 3. Deviations from the spec, and why
- Dealership status `PENDING` renamed `PENDING_VERIFICATION` and `REJECTED` added (owner's P3.A instruction; SPEC.md
  "Contract additions in P3").
- `DealershipVerified` payload: `brand_ids` → `brands` (dealerships store brand names, like pools) plus `registered_by`;
  identity became a consumer (so the registrant becomes admin).
- New endpoints in the outline: E153 (admin queue). E012 had moved to P2 earlier (P2.5).
- Buyers read quotes only once FINAL (stricter than "after the round closes"; fixes a sealed-access leak found in P3.B).
- Ranks are computed at the sealed close but revealed only when the final round opens (owner's P3.C follow-up).
- E122 is `/realtime/me/stream?pool_id=` (one pool per stream; membership checked per resource).
- The mispriced add-on is flagged by comparison (a dealer-required add-on no other offer requires), because there is no
  verified reference price for accessories to compare against (no invented data).
- Simulated dealerships are seeded by a dealer migration (as the P1 synthetic one was): they exist in every environment
  that runs migrations; marked synthetic.

## 4. Test results
- Integration tests (all green locally, and in CI on each push): dealer (onboarding, GSTIN, audit, authz), identity
  (invites, admin passkey rule, revocation), quote (transition table, revisions/immutability/diff, withdraw/deadline/cancel,
  sealed access incl. buyers before FINAL, rank IT, rank reveal, 50 parallel submissions around the deadline: every early
  one kept, every late one deadline-passed with nothing stored), realtime (E121 and E122 authz and content, messaging failure
  suite), gateway.
- Unit: RankingTest 4/4; web 22/22 (quote builder logic, compare cards, tier ladder, stock note, add-on flag, live snapshot,
  messages parity); simulator 3/3 (`node --test`).
- Authz matrix covers every built P3 endpoint (ApiOutline.BUILT_IDS).
- P1 journey (360 px): passed after every subtask (3.8–4.1 min). Three-dealer journey: see section 6 and the final run below.
- JOURNEY_RESULT_PLACEHOLDER

## 5. Bugs found and fixed
- Buyers could read SUBMITTED/REVISED quotes while the round was open (sealed leak) — now FINAL only (P3.B).
- Realtime subscribed `QuoteRoundOpened` twice (caught by the messaging-failure context) (P3.C).
- `Api.Stock` rejected the new `evidence` field (strict DTO) (P3.B).
- Jackson annotations mismatch (P2.5) and Jaeger OOM at 256 MB (capped at 20,000 traces) (P3.C).
- The quote form still called 501-era revise/withdraw stubs; the submitted card claimed buyers could see the quote (P3.B/D).
- CI flakes with real causes: MF-2/MF-3 expected exactly one duplicate (SQS is at-least-once); the realtime "redis back"
  check slept 4 s for a ~5 s resubscription (fixed on both branches before merging).
- Journey: the evidence-label check picked the hidden mobile list at desktop width (now visible-only).

## 6. Nightly results
First nightly run (manual trigger, `main` after PRs #3/#4): https://github.com/sohans1092004/convoy/actions/runs/40217726891
— all three jobs green: MF-1..MF-6; Storybook states + visual regression; both viewports + all Playwright suites, JS
budgets, Lighthouse, realtime two-instance scenarios. The three-dealer journey was added to the nightly workflow after
that run, so its first nightly execution is the next one.

## 7. Gaps for P4
- **Post-FINAL cancellation effects** (already listed): on `QuoteWithdrawn{after_final: true}` order must cancel affected
  orders/bookings, refund deposits, auto-open disputes and decrement the dealer's reliability score. P3 emits the event only.
- Dealer documents (E051) and suspension (E054) are not built; brands at registration are not checked against the catalogue.
- Hindi strings are machine-drafted and unreviewed; one catalogue name template is intentionally not translated.
- Accessory reference prices do not exist, so "inflated" is detected only relative to other offers.
- The "dealer role refused" journey flake (seen once in P3.D) was not reproduced; failure capture is in place.
- Simulator strategies `slow_responder` and `withdraws` are unit-tested but not exercised by a journey.

## 8. Needs from me
- Review and merge PR #5 (ready for review; not merged).
- Native-speaker review of `web/messages/hi.json`.
- Confirm the simulated dealerships may stay as migration-seeded synthetic rows (or move them to local-only seed data).
