# Phase 4 report: money and orders

Branch `p4/money-orders`, PR #6 (from `main` after PR #5 was merged). Date 2026-10-26. Local numbers come from one laptop
(Docker Desktop, WSL 2). Progress log: `reports/p4/PROGRESS.md`.

## 1. Summary
A confirmed buyer's offer now runs all the way to delivery or cancellation with the money correct at every step. Confirmation
records the exact ceiling and its snapshot; one transaction per pool resolves every accepted quote (count at the deadline,
max_units earlier-first, VOID below the minimum); a VOID with time left re-opens the pool once and each buyer chooses within 48 h
to keep the deposit or take a refund. A persisted saga (resolved -> deposits held -> bookings issued -> contacts released) resumes
after a crash at any boundary with the same result. Bookings are confirmed by the dealer, scheduled, and delivered (the buyer
confirms, or the dealer scans the voucher), which releases the deposit in full and posts the dealer's Rs 2,500 fee receivable.
Every cancellation rule of cancellation-rules.md is built and tested; dealer-side failures refund the buyer and open a dispute for
an admin. Disputes have categories, evidence in S3 (LocalStack), owners, a resolution history, appeals, and second-admin money
outcomes. Reviews and a reliability score (AMENDMENT-009) are live. Reconciliation compares the ledger with Razorpay and alarms.

## 2. Built (A–F)
- **P4.0 Housekeeping.** Synthetic dealerships moved out of Flyway into `deploy/local/seed/dealerships.sql`, applied by dealer's
  `DemoSeed` only with `DEMO=true` (local stack and CI default true; a lite deploy only when set); a test fails if any migration
  inserts a dealership. Demo durations have compose defaults and `.env.example` entries, so a fresh clone runs unedited.
- **P4.A Confirmation and resolution.** Confirm records `confirmed_ceiling_paise` and `ceiling_snapshot_id` (order V2, backfilled
  for earlier orders). Resolution books exactly the confirmations the tier counted (confirmed and captured before the deadline),
  at most `max_units` (earlier confirmations first, Q-012), refuses invalid tiers, and resolves every accepted quote (a quote
  nobody confirmed is VOID). Re-round (AMENDMENT-002): POOL-T09/T10, MEM-T12..T16, E042 KEEP/REFUND, MEM-T15 sweeper at the
  48 h choice deadline (`DEMO_REROUND_CHOICE_SECONDS` locally). UI: confirm card (ceiling, deadline, terms, contact consent),
  resolved price, re-round choice card with countdown.
- **P4.B Saga and crash recovery.** `order.sagas` (one per pool round) with steps FROZEN -> RESOLVED -> DEPOSITS_HELD ->
  BOOKINGS_ISSUED -> CONTACTS_RELEASED; each step is one transaction under the saga row lock, idempotent, with compensation
  (hold rejected -> booking cancelled `DEPOSIT_UNAVAILABLE` + refund; stock missing -> `STOCK_UNAVAILABLE` + refund + dispute).
  Test-only crash hooks throw inside a step (rollback, like a kill before commit) or right after its commit. Contact release: the
  buyer consents at confirmation (`contact_consents`); the saga's last step releases the contact to that quote's dealer only;
  E155 serves it through identity E157 (scope `contacts:read`); the voucher is valid only after BOOKINGS_ISSUED.
- **P4.C Deposits and reconciliation.** HELD_FOR_BOOKING on booking, RELEASED (full) on delivery, REFUNDED on every other exit;
  J-FEE (1300/4100, Rs 2,500) on delivery only. Payment emits `DepositHoldRejected` instead of failing forever. Manual refunds:
  E116 request (evidence + note) and E117 approval by a different admin (REF-T06, J-REFUND-MANUAL, audited in
  `payment.admin_audit`, self-approval refused and audited). Reconciliation (nightly 02:00 IST, E118): CAPTURED_REMOTE_NOT_LOCAL,
  LOCAL_CAPTURED_REMOTE_NOT, AMOUNT_MISMATCH, REFUND_STATUS_MISMATCH, POSSIBLE_DOUBLE_REFUND, STUCK_WEBHOOK, LEDGER_INVARIANT; each a
  row, a `ReconciliationMismatchFound` event and an ALARM; nothing auto-corrected. `PoolClosed` refund reasons now follow the
  deposit contract exactly.
- **P4.D Booking, delivery, cancellations.** Dealer confirm (48 h), set/reschedule delivery (not in the past, IST), delivery by
  the buyer (E154) or by voucher scan (E090, signature checked), overdue flag 7 days after the promised date (BKG-T07/T08). Every
  cancellation: buyer declines (ORD-T04) or cancels before booking (ORD-T03) or cancels the booking (BKG-T06); dealer admin cancels
  (BKG-T06), misses the 48 h window (BKG-T03, refund `DEALER_NOT_HONOURED`), stock vanishes (BKG-T03), quote withdrawn after FINAL
  (ORD-T10 + BKG-T06); missed confirmation deadline (ORD-T05). Dealer-side ones open a system dispute and emit `BookingCancelled`
  (reliability; stock release). `AllBookingsClosed` -> pool COMPLETED (POOL-T14). UI: buyer order timeline with one next action,
  delivery confirmation, cancel with terms, dealer bookings list with confirm / set delivery / hand over / cancel / contact.
- **P4.E Disputes, reviews, reliability, admin.** Disputes (dispute.yaml DSP-T01..T08): ten categories, 90-day window after
  delivery (Q-020), evidence via pre-signed PUT (JPEG/PNG/PDF, 10 MB, 10 files) and GET, owner, request-info with 72 h respond-by,
  appeal once within 7 days, buyer withdraw (not for system cases), full history; money outcomes need a second admin.
  Reviews (E056/E057): only a delivered booking's buyer, once. Reliability (AMENDMENT-009, closes Q-036): FINAL commitments
  honoured through delivery over 90 days, always with counts. Admin console `/admin`: refunds (retry, request, approve),
  disputes, dealer verification, reconciliation runs.
- **P4.F Journeys and report.** `LIFECYCLE=1` extends the three-dealer journey through dealer confirmation, delivery, deposit
  release, fee receivable, review and a ledger check; `CANCEL=1` has the dealer admin cancel after FINAL (refund + dispute). Both
  run nightly; the PR job keeps the single-dealer journey.

## 3. Deviations
- Buyer-confirmed delivery (E154) added next to the spec's dealer voucher scan (E090), as the P4.D instruction asks for the buyer
  to confirm delivery. Either path releases the deposit.
- New endpoints E154–E157 (SPEC.md "Contract additions in P4"); `/admin/disputes` queue; gateway routes for bookings, disputes and
  the admin paths (none existed).
- The voucher appears only once the deposit is held and the stock reserved (seconds later than before).
- tiers.json prices are synthetic; the order service prices tiers with the engine, so the vectors map each tier price to a
  discount that keeps the vector's ordering and equalities. Outcome, count, ceiling tier and resolved tier are asserted exactly.
- deposit-outcomes.json is run against the payment service with the upstream step translated to the event payment consumes
  (deposit-contract.md §3 trigger column); the upstream translation is covered by the order and pool tests.
- The crash tests throw inside the step (rolled back) or after its commit; no process is killed.
- `PoolClosed` refund reasons changed from `POOL_<end_reason>` (P1) to the contract's `POOL_<end_state>` / `QUOTE_VOID`.
- `dev-env.sh` now adds missing keys to an existing `.env` (new: `CLIENT_SECRET_ORDER`, `DEMO_REROUND_CHOICE_SECONDS`).

## 4. Test results, including the vectors executed
- tiers.json: **30/30** through the order service (`TierVectorsIT`).
- deposit-outcomes.json: **27/27** against the payment service (`PaymentIT.depositOutcomeVector`), states, ledger entries and
  reason codes; D21 includes the second-admin approval.
- Ledger invariants after **every** payment test (`@AfterEach`): every entry balances, posted rows immutable (an update is
  refused), one refund per deposit, each terminal deposit has exactly one full terminal entry, 2100/2200 at zero, 1300 only J-FEE.
- Saga crash tests: a crash before and after the commit at each of the 4 boundaries (8 runs) gives the same business result as a
  run with no crash (events by type, order and booking states, vouchers, contact releases, refunds).
- Integration tests (local): order 47 (OrderIT 6, LifecycleIT 11, TierVectorsIT 30), payment 47 (PaymentIT 42, messaging 5),
  pool 19 + 6, dealer 8, identity 11, gateway 7; catalog, quote, realtime unchanged and green.
- Web: unit 26/26 (timeline logic, messages parity), typecheck and lint clean.
- Journeys (local, 360 px, once each after the fixes):
  - single-dealer (PR mode): **passed** (4.3 min; first run failed: voucher never issued, the quote-version bug below);
  - full lifecycle (3 dealers, `LIFECYCLE=1`): **passed** (4.6 min) — dealer confirmed and scheduled, released contact shown,
    buyer confirmed delivery, deposit RELEASED, J-FEE 1300/4100 Rs 2,500, ledger invariants, review posted (first run failed:
    the delivery success message vanished with its card, fixed in the UI);
  - cancellation (`CANCEL=1`): **failed twice; stopped per the 2-attempt rule.** Run 1: no refund (event-version bug below).
    Run 2: everything passed (dealer cancel, order ended, case opened, deposit REFUNDED, ledger J-CAPTURE/J-HOLD/J-REFUND and
    invariants) up to a test locator that matched 3 elements on the dispute page. Locator fixed, not re-run; nightly verifies.

## 5. Bugs found
- P1 `PoolClosed` refunds used the end *reason* (`POOL_MINIMUM_NOT_REACHED`), not the contract's `POOL_EXPIRED` (D03).
- Resolution booked every CONFIRMED order, including any the tier did not count (captured or confirmed at/after the deadline).
- A quote nobody confirmed never resolved, so the pool's VOID count missed it (tiers.json T10/T30).
- Payment's hold threw forever (message redelivered) when no captured deposit existed; now `DepositHoldRejected`.
- The audit row of a refused self-approval rolled back with the refusal; now written in its own transaction.
- Order V2's new constraint failed on orders confirmed before P4; the migration now backfills from the CEILING snapshot.
- quote stamped `StockReserved`/`StockUnavailable` with the quote's current version without bumping it, so order dropped them as
  stale and the saga never issued vouchers (P1 bug, exposed because the voucher now waits for the reservation).
- order's resolution stamped booking events (`DepositHoldRequested`) with version +2 while the row moved +1, so a later
  cancellation's `DepositRefundRequested` had the same version and payment dropped it: no refund. Regression check added.
- The buyer's "Delivery confirmed" message disappeared with its card (moved outside the card).
- D21 depended on the refund worker picking this refund among 10 due ones (flaked in CI); it now drives its own refund.
- New endpoints answered 400 instead of 403 to the wrong role (body validation before the role check); `@RequiresRole` added.

## 6. Nightly
The nightly workflow now runs the full lifecycle journey (3 dealers) and the cancellation journey after the PR-mode suites.
No nightly has run on this branch yet (nightly runs on `main`); first results come after the merge or a manual dispatch.

## 7. Gaps for P5
- Stock release on `BookingCancelled` in quote is not built (order emits it; quote ignores it).
- Notifications (email/SMS) for every event are not built; the UI polls.
- The overdue sweep runs with the order sweeper, not a 09:00 IST daily job.
- Invoice confirmation (E101), dealer documents (E051) and suspension (E054) are not built.
- Evidence files are not virus-scanned, and the uploaded object's real size/type is not re-checked after upload.
- Reconciliation does not list orphan Razorpay orders (no orders-list call); it checks each local deposit's order instead.
- Reliability counts start at P4 (no backfill of earlier outcomes).
- Hindi strings for P4 are machine-drafted and unreviewed.

## 8. Needs from me
- Review and merge PR #6.
- Native-speaker review of the new Hindi strings.
- Whether `users.json` (seeded test users) should also load only with `DEMO=true`.
- Your local `.env` gained `CLIENT_SECRET_ORDER` (random) and `DEMO_REROUND_CHOICE_SECONDS=120`; nothing else changed.
