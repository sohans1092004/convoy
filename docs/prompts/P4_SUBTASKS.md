# Convoy Phase 4: money and orders (7 subtasks)

Start a fresh agent session for each subtask. Paste the working-style block, then one subtask, and send me the short report.

## Working style (paste above every subtask)

```
You are working on Convoy Phase 4 (money and orders), one subtask at a time. Read PLAN.md, SPEC.md
(+ amendments), reports/P3_REPORT.md and reports/p4/PROGRESS.md (create it if missing) first.
Ground rules: no AWS, no paid services, integer paise, DB clock decides deadlines, no invented
data, secrets only in .env, stop and ask before changing a frozen rule.
Branch: p4/money-orders (from main). Open a draft PR on the first push so CI runs.

Working style:
- Build first; run only unit tests and the integration tests of what you changed. Keep the
  Docker stack running.
- Run the browser journey ONCE at the end.
- No load tests, Lighthouse, visual-baseline updates or repeated-flake runs.
- When a test fails: read the error, make one targeted fix. If the same test still fails after 2
  attempts, stop and report it. Never revert working code or loosen a test.
- Money rule: after every money test, assert the ledger invariants (every journal balances, posted
  entries are immutable, each deposit ends REFUNDED or RELEASED, the full amount, exactly once).
- Time box: 60 minutes. Append 2-3 lines to reports/p4/PROGRESS.md every ~20 minutes and print them.

Report (max 15 lines): DONE / TESTS RUN / PROBLEMS / NEEDS FROM ME.
```

---

## P4.0: Housekeeping (about 20 min)
- **Demo seed data:** move the synthetic dealerships, including the P1 one, out of Flyway migrations into a seed script with a `demo` profile. Migrations must contain no demo data, so a real environment never receives fake dealers. The local stack and CI run the seed automatically; the lite deploy runs it only with `DEMO=true`.
- **Demo durations:** add `DEMO_FINAL_ROUND_SECONDS=20`, and every other demo duration, to `.env.example` and the config defaults, so a fresh clone works without a hand-edited `.env`.
- Run both journeys once.

## P4.A: Confirmation and tier resolution (§2.2), plus the re-round choice
- **Confirm at the ceiling:** a confirmation records the exact ceiling price and the price snapshot it came from.
- **Resolution at the confirmation deadline, one transaction per pool, in order:**
  1. count confirmed buyers (confirmation recorded and deposit captured)
  2. pick the tier
  3. price every confirmed buyer at that tier, never above their ceiling
  4. apply `max_units` with earlier commits first (Q-012)
  5. if the count is below the minimum fulfilment, mark the quote VOID
- **Every vector in `tiers.json` is run through the order service,** not only through the engine.
- **Re-round choice (AMENDMENT-002):** when a quote goes VOID and the pool is eligible, each buyer gets 48 h to choose "keep my commitment" or "refund now". No answer means a refund. If the pool isn't eligible, refund automatically. Test it with short demo durations.
- **Buyer UI:** the confirm screen (ceiling, deadline, terms), the resolved-price result, and the re-round choice screen with its countdown.

## P4.B: Order saga and crash recovery
- **Persisted saga state** for the path from acceptances frozen to tier resolved to deposits held to bookings issued to contacts released. Every step is idempotent and has its compensation (refund, release, cancel booking).
- **Crash test at every saga boundary** using test-only hooks: kill or throw after each step's commit and before the next. On restart the saga resumes, and the business result is identical to a run with no crash (no double refunds, no missing bookings, ledger balanced).
- **Contact release:** a buyer's contact details go **only** to the dealer whose quote they confirmed, with a consent record. Test that competing dealers get nothing.

## P4.C: Deposit lifecycle end to end, plus reconciliation
- **States:** HELD_FOR_BOOKING when a booking is issued, RELEASED (full refund) when delivery is confirmed (AMENDMENT-003), REFUNDED on every other exit.
- **Success fee:** a ₹2,500 receivable from the dealer (J-FEE) posted on delivery, never from the buyer's deposit (AMENDMENT-007).
- **Outcome vectors:** run **every row of `deposit-outcomes.json`** against the payment service and assert states, ledger entries and reason codes.
- **Reconciliation job:** compare the ledger with the Razorpay fake's records, report mismatches and webhooks stuck beyond a threshold, and raise an alarm. Test it with a deliberately missing webhook and a deliberately extra payment.
- **Refund failures:** retry with backoff, then go to the admin queue. A manual refund needs a second admin (AMENDMENT-001).

## P4.D: Booking, delivery and cancellations
- **Booking flow:**
  1. the dealer confirms the booking within 48 h (Q-022)
  2. the dealer sets a delivery date
  3. the buyer confirms delivery, which releases the deposit and posts the fee
  4. after the delivery date plus a 7-day grace period, the booking is flagged overdue and the buyer is prompted to open a dispute (Q-019)
- **Every cancellation rule in §2.6, each with a test.** This replaces the 501s:
  - buyer cancels after confirming: full refund in test mode, recorded as a buyer cancellation
  - dealer withdraws or cancels after FINAL, doesn't honour the price, or stock disappears: buyer refund, booking cancelled, a dispute auto-opened, the dealer's reliability affected, and admin review
  - buyer misses the confirmation deadline: counted as declined, refund
- **Buyer order timeline UI:** accepted → confirmation pending → deposit outcome → booking issued → dealer confirmed → delivery scheduled → delivered or cancelled, with the next action always obvious. Keep the QR voucher.
- **Dealer UI:** a bookings list with confirm and set-delivery actions.

## P4.E: Disputes, reviews, reliability, admin
- **Disputes:** categories, evidence attachments (S3 via LocalStack, pre-signed URLs, type and size limits), status, owner, and a resolution history. Disputes can be opened up to 90 days after delivery (Q-020). Refunds and cancellations carry explicit reasons.
- **Reviews:** only a buyer with a delivered booking can review that dealer, once per booking.
- **Reliability score (closes Q-036):** a simple, documented formula, for example the share of FINAL quotes honoured through to delivery over the last 90 days, plus counts. Show it to buyers with the sample size, and never as a bare percentage without the counts. Record it as an amendment.
- **Admin console (passkey admins):** refunds queue with second-admin approval for manual actions, disputes queue, and the existing verification queue. All actions are audited.

## P4.F: Full lifecycle journey and the P4 report
- **Journey:** extend the 3-dealer journey through to delivery: confirm, resolve, book, dealer confirms, deliver, deposit released, fee receivable posted, review. Assert the ledger at the end. It runs nightly; the PR job keeps the single-dealer journey.
- **Cancellation journey:** add a short one, a dealer cancelling after FINAL, which ends in refund plus dispute. It also runs nightly.
- **Report:** write `reports/P4_REPORT.md` with these headings:
  1. Summary
  2. Built (A–F)
  3. Deviations
  4. Test results, including the vectors executed
  5. Bugs found
  6. Nightly
  7. Gaps for P5
  8. Needs from me

  Then mark the PR ready for review.
