# Phase 5 report: operational completeness

Branch `p5/ops`, PR #7 (from `main` after PR #6 was merged). Date 2026-10-26. Local numbers come from one laptop
(Docker Desktop, WSL 2, lean compose profile). Progress log: `reports/p5/PROGRESS.md`. Phase prompt: `docs/prompts/P5_SUBTASKS.md`.

## 1. Summary
Convoy now tells people what happened and keeps a record nobody can quietly change. A notification service turns user-facing
events into exactly one in-app message, one email (SMTP: Mailpit locally) and, for opted-in devices, one payload-free web push per
user per event, whatever order or how often the events arrive. Payment and refund messages ignore preferences. With the
service stopped, a buyer can still join, commit and confirm, and on restart the backlog is delivered once. An audit service
records every event of every service in an append-only, hash-chained log; `verify-chain` detects an edited row. Admins can read
the log, and every read is itself audited. DPDP data rights are built: per-purpose notices and consents with history,
a one-file export from every service, and erasure tracked until every service confirms. Financial rows are kept and pseudonymised
for their retention periods. Clarifications let a buyer ask about a FINAL offer and the dealer answer, with contact details
refused both ways. Both sides are notified. Marketplace analytics (admin) and dealer analytics (own dealership only) show
every metrics.md figure with its sample size, labelled "simulated" while the data is synthetic. A version guard
(outbox unique per aggregate version, a `stale_distinct` metric that must be 0) would have caught both P4 bugs.

## 2. Built (0, A–E)
**P5.0 Safety and housekeeping**
- Outbox `unique (aggregate_id, aggregate_version)` on new rows in every service. It found and fixed 6 double-version spots in order and quote `RankChanged` reusing quote versions (now its own stream).
- Consumer `messaging.stale_distinct` counter, asserted 0 after every service test suite and the journeys. All 9 consumers must answer the scrape.
- Compose profiles: a lean default (1 realtime, no Jaeger), `ha` (realtime-2) and `tracing` (Jaeger). Documented in the README.
- Seeded test users load only with `DEMO=true`.
- Visual baseline for the 360 px price breakdown refreshed from CI's own Linux render (owner-approved).

**P5.A Notification (wave 2)** (AMENDMENT-010, -011)
- EVENTS subscription kind (inbox dedupe, no stale rule).
- A recipient graph (pool→buyers with membership versions, order/quote/booking/dispute links) fans each event out once its recipients are known.
- In-app inbox E130–E132, E158, E159, plus a bell and an inbox page.
- Email over SMTP with backoff and a DEAD state. en templates, plus hi templates marked as drafts. Preferences per user and channel.
- Isolation run passed. The Razorpay-fake guard is used by every journey. `scripts/reset-demo.sh` added.

**P5.B Web push and PWA**
- Payload-free VAPID (ES256, JDK only): the service worker fetches the newest message over the user's session.
- E160–E162, endpoint allow-list (SSRF), `scripts/gen-vapid.mjs`.
- Manifest, icons, and a service worker that caches only `/offline.html`.

**P5.C Audit (wave 3)** (AMENDMENT-012)
- Every event of every service, append-only: the app role has INSERT + SELECT only, and a trigger rejects UPDATE/DELETE. Each row has the previous row's hash, actor, correlation id and a sensitive flag.
- `verify-chain` command, outbox-lag and audit-count reconciliation metrics.
- E163 viewer (passkey admins, reads audited), runbook.
- Added the Prometheus registry that was never on the classpath.

**P5.D Privacy (DPDP)**
- Notices E168 and consents with history and withdrawal. E157 refuses a dealer contact after a `DEALER_DISCLOSURE` withdrawal.
- `UserExportRequested`: each service contributes its part to one JSON file in private encrypted S3 (LocalStack), with 15-min audited links and 24 h cleanup.
- `ErasureRequested` tracked to every confirmation. Per-service delete/pseudonymise, including outbox payloads.
- Daily retention jobs. `docs/privacy/retention.md` cites the periods (legal review flagged).

**P5.E Clarifications and analytics** (AMENDMENT-013)
- E071/E072: 7 fixed question types, 300/500 chars. Phone/email/link refused both ways, including Devanagari digits.
- `QuoteFinalised.figures` added; E101 invoice confirmation and `InvoiceConfirmed`.
- E169 marketplace analytics: fill, response, ≥ 2 valid quotes, conversion, cancellation, and the four savings labels kept apart.
- Dispute evidence files are covered by erasure and retention.

**P5 gaps (this session)**
- Clarification notifications:
  - new `ClarificationAsked` event (own stream per clarification, carries the quote's author as `dealer_user_id`) gives that dealer user `CLARIFICATION_ASKED`
  - `ClarificationAnswered` gives every current committed member of the pool `CLARIFICATION_ANSWERED`
  - in-app + email, same dedupe and preferences
- E058 dealer analytics (dealer_admin): response rate, win rate and the latest reliability score. Only for the dealership in the caller's session; no request input can name another dealership (AZ-T5). Web page `/dealer/analytics`.
- E169 gains `void_rate` (VOID ÷ quotes with ≥ 1 acceptance), `refund_latency_hours` (RefundRequested → RefundCompleted, p50/p95) and `dispute_resolution_days` (DisputeOpened → DisputeResolved, p50/p95). Each is shown with n and the "simulated" badge.

**P5.F**
- The nightly lifecycle journey now checks, for this run, that the buyer's in-app messages arrived:
  - deposit, comparison, confirmation needed, tier resolved, booking confirmed and delivery scheduled
  - each of deposit, confirmation needed, tier resolved, booking confirmed and delivery scheduled exactly once
  - each one's email is in Mailpit
- Every journey's `afterAll` now ends with `verify-chain`, which must report an intact chain with at least one row.

## 3. Deviations
- **E058 lives in audit, not dealer** (api-outline updated). Response and win rate need invitations, submissions, finals and bookings. Audit already holds every event and E169 computes the same figures marketplace-wide. Putting E058 in dealer would mean new replicas of four event types.
- **`CLARIFICATION_ASKED` goes to the quote's author only**, not every staff member of the dealership: no event carries dealership membership. ponytail note in `Clarifications.ask`. The upgrade path is identity listing a dealership's active staff.
- **`CLARIFICATION_ANSWERED` uses every current member**: the answer is on another aggregate, so its version is not comparable with pool membership versions. A buyer who commits after the answer also gets it, which is harmless because the answer is visible to them anyway.
- Email is SMTP everywhere (AMENDMENT-011), not the SES API: LocalStack community SES never relays to Mailpit.
- Web push carries no payload (no RFC 8291 encryption needed; nothing personal goes through the push vendor).
- Reliability on E058 is the latest `ReliabilityScoreChanged` score, so it is null until the dealer's first outcome. The counts stay on the dealer profile (E057).

## 4. Test results
| Suite | Result |
|---|---|
| NotificationIT | 5/5: reversed backlog ×2 gives exactly one message + email; preferences; DEAD; push; **clarifications: dealer asked, members answered, leaver excluded, once each** |
| QuoteIT | 13/13 (ClarificationAsked carries the quote's author) |
| AuditIT | 4/4: chain + tamper; viewer audited; E169 including **VOID rate 1/2, refund p50 3.0 h / p95 3.9 h with an open refund not counted, dispute 3.0 days**; **E058: own figures, `?dealership_id=` of another dealer ignored, staff/buyer/platform admin 403, no token 401** |
| IdentityIT / OrderIT / LifecycleIT / PaymentIT / CatalogIT / TierVectorsIT | 11/11, 6/6, 12/12, 42/42, 3/3, 30/30 (P5.D/E, unchanged since) |
| validate_spec | 14/14 |
| web typecheck / lint / unit | clean / clean / 26/26 |
| pwa.spec / privacy.spec | 4/4 / pass (P5.B, P5.D) |
| Journey, single dealer with `LIFECYCLE=1` (mobile-360) | PASS once, 5.7 min: 12 in-app messages for the run, each with its email in Mailpit (11 kinds, including `REFUND_DONE`, because a release is refunded with kind `RELEASE`, deposit-contract.md); deposit RELEASED, ledger `t\|0\|1` and `t\|0`; stale_distinct 0 from all 9 consumers; `audit chain intact: 148 rows`; axe clean |

**P5.X vacuous-check audit** (checks that could pass without testing anything, each proved by breaking it):

| # | Check | Why it was vacuous | Fix | Proof |
|---|---|---|---|---|
| 1 | ServiceTest `stale_distinct` guard | No-op in every messaging-off suite; passed with a missing consumer | Messaging on → consumer required | Missing consumer now fails |
| 2 | Journey `afterAll` ledger check | `try/catch` swallowed its own failed `expect` | Removed the try/catch | Missing DB: old PASS, new FAIL |
| 3 | Journey + privacy "balanced" | `sum(dr) = sum(cr)` is true on an empty ledger | `count(*) > 0 and …` | Empty `journal_lines`: old t, new f |
| 4 | PaymentIT immutability | Skipped when there were no rows | Checks the triggers themselves | PaymentIT 42/42 |
| 5 | OrderIT `bookings_count` | A missing field read as 0 | `required()` | OrderIT 6/6 |
| 6 | az-t6 revocation probe | A network error (status 0) counted as revoked | 401/403 only | — |

## 5. Bugs found
- P5.0 guard: quote `RankChanged` reused quote versions. Order had 6 double-version writes.
- P5.A: concurrent link/event commits in notification missed each other (READ COMMITTED). Graph writes now serialise on an advisory lock.
- P5.A: the bell failed axe `label-content-name-mismatch`.
- P5.C: `/actuator/prometheus` was 401, then 404 (no registry) on every service since P1, so `stale_distinct` had never been read.
- P5.E: `InvoiceConfirmed` was in the catalogue but never emitted (E101 never built).
- P5.X: the 6 vacuous checks above.
- **Refund retries burned by a lock race (payment, since P2).** `initiate()` read the refund with `for update skip locked` through
  `queryForMap`. When another attempt held the row, or had already moved it to `REFUND_INITIATED`, the empty result or the
  state check threw. `attempt()` then counted that as a failed Razorpay call (`attempts + 1`, rescheduled). In production, a lost
  race shortens the 5-step retry schedule. In CI, the 2 s background worker raced D21's own attempts and gave it a 6th
  (CI run 40321065175 at 850ebd2). Fix: a locked or no-longer-due refund is a no-op. New check in PaymentIT: hold the row lock and
  attempt (0 attempts counted), then attempt an already-initiated refund (still 1). On the old code it fails with `attempts=2`,
  `Incorrect result size: expected 1, actual 0`. PaymentIT 42/42.

## 6. Nightly
- **Run 40318303340 on `p5/ops` at 63678b6 (manual dispatch): success.** Every step passed, including:
  - all Playwright suites (360 px + desktop)
  - the full lifecycle journey (3 dealers) with the P5.F notification, email and verify-chain checks
  - the cancellation journey
- The previous nightly (run 40244851654, `main` before P5) failed only the 360 px price-breakdown visual baseline. That baseline was refreshed in P5.0.

## 7. Gaps for P6 (deployment)
- SES SMTP credentials and a verified sender domain (AMENDMENT-011 keys exist, unset). DKIM/SPF.
- VAPID keys and the push-endpoint allow-list as deployment secrets/config.
- Real S3 buckets for exports and dispute evidence (private, SSE, lifecycle rules matching retention.md). LocalStack only today.
- Audit log: a WORM copy or off-site anchoring of the chain head. Superuser access is the remaining tamper path. Audit-log pruning is recorded as P8.
- Prometheus scraping and alerts (stale_distinct > 0, outbox lag, DEAD deliveries, reconciliation) are not wired to any alerting.
- Analytics computed on every read (ponytail note): materialise daily once audit_log is large.
- Clarification notice to all dealership staff (needs identity staff listing).
- `GatewayIT.otpStartIsRateLimitedEvenWithoutRedis` suspected flake (one failure on PR #6), still open.

## 8. Needs from me
- Review PR #7 (marked ready; not merged).
- Legal review of the retention periods in `docs/privacy/retention.md`. Native-speaker review of the Hindi drafts (P5 templates and strings).
- P2_SUBTASKS.md–P4_SUBTASKS.md were not in `~/Downloads` (only P5). Drop them there if you want them in `docs/prompts/` too.
