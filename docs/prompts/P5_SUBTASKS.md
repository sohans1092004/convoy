# Convoy Phase 5: operational completeness (7 subtasks)

Merge PR #6 first. Start a fresh agent session per subtask: paste the working-style block, then one subtask, and send me the short report.

## Working style (paste above every subtask)

```
You are working on Convoy Phase 5 (operational completeness), one subtask at a time. Read PLAN.md,
SPEC.md (+ amendments), reports/P4_REPORT.md and reports/p5/PROGRESS.md (create if missing) first.
Ground rules: no AWS, no paid services, integer paise, DB clock decides deadlines, no invented
data, secrets only in .env, stop and ask before changing a frozen rule.
Branch: p5/ops (from main). Open a draft PR on the first push.

Working style:
- Build first; run only unit tests and the integration tests of what you changed. Keep the
  Docker stack running (lean profile, see P5.0).
- Run the single-dealer browser journey ONCE at the end of each subtask.
- No load tests, Lighthouse, visual-baseline updates or repeated-flake runs.
- When a test fails: read the error, make one targeted fix. If the same test still fails after 2
  attempts, stop and report it. Never revert working code or loosen a test.
- Money rule still applies: ledger invariants after every money-related test.
- Time box: 60 minutes. Append 2-3 lines to reports/p5/PROGRESS.md every ~20 minutes and print them.

Report (max 15 lines): DONE / TESTS RUN / PROBLEMS / NEEDS FROM ME.
```

---

## P5.0: Safety and housekeeping (about 30 min)
1. **Version safety guard.** Both P4 bugs were events dropped silently as "stale" because a producer reused a version.
   - Producer side: a unique constraint on `(aggregate_id, aggregate_version)` in every outbox, so a reused version fails loudly at write time.
   - Consumer side: when an event is dropped as stale but has a **different `event_id`** from the one already applied at that version, count it in a `messaging.stale_distinct` metric and log a warning.
   - Assert that metric is **zero** at the end of every journey and every integration test suite.
2. **Lean local stack.** Add Docker Compose profiles:
   - the default runs 1 realtime instance
   - a `ha` profile adds `realtime-2`
   - Jaeger is optional in a `tracing` profile, which the journey's trace assertion enables

   CI and nightly use the profiles they need. Document the commands in the README.
3. **Seeded users:** load users.json test users only with the demo profile, the same as the dealers. CI and local runs use the demo profile.
4. **Cancellation journey:** check last night's nightly run. If it failed, fix it (2-attempt rule). Report the result either way.

## P5.A: Notification service (wave 2)
- A new `notification` service that consumes the events which need a message: pool ready, round opened and closed, comparison ready, confirmation needed, tier resolved, refund done, booking confirmed, delivery scheduled, dispute updates.
- **Channels:**
  - in-app inbox: API plus a notification bell and inbox page
  - email: through the SES API, with LocalStack locally and Mailpit as the sink
- **Content:** templates in English and Hindi (Hindi marked as a draft), and preferences per user and channel. Payment and refund messages are always sent.
- **Sending:** idempotent via the inbox dedupe, so one event gives exactly one message per channel. Retries with backoff and a DLQ.
- **Isolation test (one targeted test):** with notification stopped, a buyer can still join, commit and confirm. On restart, the backlog is delivered exactly once.

## P5.B: Web push and PWA
- **Web push:** VAPID keys generated locally into `.env`, subscribe and unsubscribe from settings, sent by notification for opted-in events.
- **PWA:** installable manifest and icons, a service worker with an offline fallback page. Never cache API responses holding personal or money data.
- **Tests:** Playwright checks the manifest and offline page. Push delivery is tested at the service level against a local push endpoint stub.

## P5.C: Audit service (wave 3)
- A new `audit` service consuming **every** service's events from standard queues.
- **Storage is append-only:** the application role has INSERT and SELECT only, plus a trigger that rejects UPDATE and DELETE. Each row includes the hash of the previous row (a hash chain), the actor, the correlation ID and a sensitive-access flag.
- **Checks:**
  - a `verify-chain` command detects tampering, tested by editing a row as a superuser
  - an outbox-lag and audit-count reconciliation metric
- **Admin audit viewer:** filter by actor, aggregate, type and time, for passkey admins only. Reading the viewer is itself audited.

## P5.D: Privacy (DPDP Act and Rules)
- **Consent:**
  - a privacy notice for each purpose
  - consent records with history, and a settings page to view and withdraw consent where withdrawal is allowed
- **Data export:** a `UserExportRequested` event; each service contributes its part, and the user downloads one JSON file (pre-signed S3 URL with an expiry).
- **Account deletion:**
  - a `UserDeletionRequested` event, tracked until every service confirms
  - per the retention table, payments, ledger, disputes, invoices and audit are **kept** for their periods and pseudonymised, not deleted
  - document the categories and periods, and add a job per service that enforces them
- **Tests:**
  - deletion completes across all services
  - kept financial rows are still balanced
  - nothing personal remains in deletable tables
  - the export contains each service's data

## P5.E: Clarifications and marketplace analytics
- **Structured clarifications (PLAN §3.1 item 11):**
  - a buyer asks about a specific quote using a fixed question type plus a short text
  - the dealer answers, and the answer is visible to that pool's buyers
  - phone numbers, emails and links are blocked in both directions before the consent point, with server-side validation and a test
- **Analytics views (admin):**
  - pool fill rate and time to fill
  - dealer response rate
  - pools with at least 2 valid quotes
  - acceptance to confirmation to delivery conversion
  - cancellation rate
  - savings using the §2.5 labels only

  Every figure from simulated data is labelled **"simulated"**.

## P5.F: Journeys and the P5 report
- Extend the full-lifecycle journey (nightly) to check that the right in-app and email notifications arrived, and that the audit chain verifies at the end.
- Write `reports/P5_REPORT.md` with these headings:
  1. Summary
  2. Built (0, A–E)
  3. Deviations
  4. Test results
  5. Bugs found
  6. Nightly
  7. Gaps for P6 (deployment)
  8. Needs from me

  Then mark the PR ready for review.
