# P1 Report — Walking skeleton (Convoy)

Branch `p1/skeleton` · 2026-10-24 · Local stack only (Docker Compose); no AWS resources, no paid services.

## 1. Summary
The first complete buyer journey runs end to end through real service boundaries and real events:

> sign in (email OTP, Mailpit) → browse a model → on-road estimate (engine snapshot) → join a pool → commit with a ₹999 test deposit (Razorpay test-mode fake, signed webhook) → one dealer submits a sealed quote → buyer compares (ceiling + labelled projection) → accepts → confirms at the ceiling → tier resolves in one transaction per pool → booking voucher with an ES256-signed QR.

- **Browser journey (Playwright):** **passes at 360 px and 1280 px** (2/2, 4.3 min wall clock, both journeys in parallel on separate pools), with axe finding **no serious or critical violations on any of the 11 pages visited** (sign-in, catalogue, model + estimate, pool, dealer inbox, dealer quote form, compare, orders, order confirm, order voucher). The ledger check after the run: every entry balanced, no deposit in an invalid state.
- **API journey** (`tools/journey/api-journey.mjs`): passed twice, 230–234 s in demo mode; final price ₹6,63,475 ≤ ceiling ₹6,63,475 (one buyer stays in the 1–4 tier).
- **Screen recording:** `reports/p1-media/journey-buyer-360px.webm` and `reports/p1-media/journey-buyer-desktop.webm` (git-ignored, ~10 MB each) (Playwright video of the journey, local file).
- **Trace:** Jaeger holds traces spanning web → gateway → pool → SNS/SQS → order for the accept call (order builds its acceptance read model from `AcceptanceChanged`). Both Playwright projects assert that a trace containing **web, gateway, pool and order** exists for their run, and it does.

What exists:
- 8 services (gateway, identity, catalog, dealer, pool, quote, order, payment) on `libs/platform` (problem+json, Idempotency-Key, internal JWT with JWKS rotation, transactional outbox + SQS inbox, aggregate versions, W3C trace context through SNS/SQS message attributes, structured ECS logs with trace ids, state-machine tables).
- `libs/price-engine` (Java) and `packages/price-engine` (TypeScript), both byte-for-byte on the vectors.
- `libs/contracts`: Java records and TS types generated from the 67 event schemas; publisher tests validate every outbox row against its schema.
- Next.js 16 web (BFF with HttpOnly session cookie, CSRF double-submit, role guards in `proxy.ts`), design-system components with Storybook, next-intl (en, en-IN numbers), typed API client generated from each service's springdoc OpenAPI.
- Local stack: Postgres 16 (one schema + role per service), Redis, LocalStack, Mailpit, OTel Collector + Jaeger, Razorpay fake, seed (catalogue, dealership, buyers, dealer staff, admin).

## 2. Amendments applied
All six were applied in one commit before any code (`e7c314e`), then `validate_spec.py` 13/13.

| Amendment | What changed |
|---|---|
| AMENDMENT-001 | Every manual refund/adjustment/override needs a second, different admin (refund.yaml REF-T06, dispute.yaml DSP-T05, authz AZ-INT-08). Automatic refunds need none. Q-040 superseded. |
| AMENDMENT-002 | VOID with re-round eligibility keeps deposits by buyer choice (48 h; no response = refund): membership state REROUND_CHOICE + MEM-T12…T16, POOL-T09/T10, ORD-T06, E042, vectors D24–D27. Q-007 superseded. |
| AMENDMENT-003 | APPLIED_TO_BOOKING → HELD_FOR_BOOKING; SETTLED → RELEASED (refund of kind RELEASE); success fee is a dealer receivable (1300/4100, J-FEE); account 2300 removed. Q-041 added (fee amount). |
| AMENDMENT-004 | Dealer insurance ranks only if binding and exactly baseline cover; engine warning `DEALER_INSURANCE_NOT_RANKABLE`; vectors P052–P054; quote form shows it. |
| AMENDMENT-005 | Compare shows ceiling and "If the count stays at N … not guaranteed" (N = accepting buyers in DECISION_OPEN, confirmed buyers in CONFIRMATION). |
| AMENDMENT-006 | Ordering consumers use FIFO queues; notification/audit standard queues. **LocalStack 4.14.0 reproduces SNS FIFO → SQS standard delivery** (platform test `MessagingIT`), so no local workaround is needed. |

Also: pre-commit copy in cancellation-rules.md aligned with AMENDMENT-002 (missed in Step 0, fixed in P1). P1 contract additions (no rule change) are logged in SPEC.md §6.

## 3. Architecture as built
**Services and the P1 transitions they implement** (every other (from, to) pair → 409 invalid-transition; specified but unbuilt → 501 not-implemented-in-p1):

| Service | P1 scope as built | Transitions built |
|---|---|---|
| gateway | Routes with per-route audience; session validation via identity, Redis cache ≤ 30 s, fail closed; ES256 internal JWT (120 s, kid, key files with not-before for rotation); strips client identity headers; Redis rate limits (OTP start, join) with in-memory fallback | — |
| identity | Email OTP (hashed, 10 min, 5 attempts), hashed sessions, roles, dealership staff, service tokens (client credentials, per-audience scopes), JWKS, consent records (DEALER_DISCLOSURE, append-only), local user seed | — (passkeys E003–E005 → 501) |
| catalog | Seed generated from models.yaml: VERIFIED variant prices of the 7 P1 models × 4 cities; ruleset recorded with hash; on-road estimate with stored snapshot | — |
| dealer | One synthetic verified dealership (Pune, Maruti Suzuki), eligibility, profile | — |
| pool | Create/find, join, commit (deposit first), leave, accept/withdraw; DB-clock deadlines; sweeper | POOL-T01/T02/T03/T05/T07/T08; MEM-T01/T02/T03/T04/T05/T07/T08/T09/T10/T11; RND-T01/T02 |
| quote | Round replicas; draft/submit priced by the Java engine (tiers validated on engine prices); buyer comparison; summary (finalises after the final deadline); stock reservation | QUO-T01/T02/T04/T08/T09 |
| order | Orders with CEILING snapshot; confirm (hash echo, capacity, DB-clock deadline); one-transaction-per-pool resolution; bookings + vouchers; saga steps HOLD_DEPOSIT/RESERVE_STOCK; dealer confirm | ORD-T01/T02/T05/T06/T07; BKG-T01/T02 |
| payment | Deposits (deterministic key), callback → PENDING only, signed webhook → CAPTURED, dedupe, ledger (immutable, balanced, deferred trigger), hold, refund worker | PAY-T01/T02/T03/T04/T06/T07/T08; REF-T01/T02/T03 |

**Events actually flowing in the journey:** PoolCreated, MemberJoined, MemberCommitted, PoolReady, QuoteRoundOpened, QuoteSubmitted, RoundClosed (×2), FinalRoundOpened, QuoteFinalised, DecisionOpened, AcceptanceChanged, AcceptancesFrozen, OrderCreated, ConfirmationRecorded, TierResolved, BookingIssued, BookingsIssued, DepositHoldRequested, DepositHeld, StockReservationRequested, StockReserved, PoolBookingStarted, PaymentCaptured, UserRegistered (+ RefundRequested/RefundCompleted on leave). Pool events go through `convoy-pool-events.fifo` (MessageGroupId = pool id); each consumer has its own queue + DLQ with a filter policy on `event_type`.

**Deviations from the spec, with reasons:**
1. **LocalStack 4.14.0**, not current: current images exit without an account auth token. 4.14.0 is the last community (Apache-2.0) release that runs without one.
2. **Contract additions** (SPEC.md §6): problem type `not-implemented-in-p1`; E029 internal model lookup (pool → catalog, scope `models:read`); variant facts on QuoteRoundOpened/FinalRoundOpened; tiers + engine inputs on QuoteFinalised; extra consumers (quote ← membership/acceptance/confirmation events, order ← AcceptanceChanged). Reason: avoid sync calls the spec says must not be needed (catalog down → quoting still works).
3. **Version ordering** is opt-in per subscription (NONE / MONOTONIC / STRICT): with filtered subscriptions most consumers never see every version, so "gap → park" applies only to STRICT consumers (none in P1).
4. **Topology** (topics, queues, DLQs, subscriptions) is created idempotently by each service at startup; in AWS (P6) it moves to infrastructure code.
5. **Idempotency responses** are stored after the business transaction commits, not inside it; abandoned claims (> 60 s) are re-run, and business uniqueness keeps that safe (`ponytail:` note in `IdempotencyStore`).
6. **POST /pools** finds the open pool for (model, city, month) before creating one; there is no pool search endpoint in P1.
7. **Scheduler**: deadlines are swept every 5 s (demo) by pool and order; EventBridge wake-ups are P6.
8. **Pool minimum** has no value in PLAN; demo uses 1, logged as Q-042.

## 4. Test results
| Category | Count | Result |
|---|---|---|
| Price engine vectors, Java (`libs/price-engine`) | 84 (54 pricing + 30 tier, incl. snapshot SHA-256) | pass |
| Price engine vectors, TypeScript (`packages/price-engine`) | 86 (84 vectors + 2 count checks) | pass |
| Platform messaging (Postgres + LocalStack) | 3 (happy path FIFO + standard, duplicate delivery, stale version) | pass |
| Service integration (Testcontainers Postgres, one class per service) | 39 — pool 8, payment 8, order 5, quote 5, identity 5, gateway 5, catalog 2, dealer 1 | pass |
| Razorpay fake contract | 3 | pass |
| Browser E2E (Playwright, 360 px + 1280 px, axe on every visited page) | 2 | 2 pass (axe: 0 serious/critical on 11 pages × 2 widths) |
| Spec validator | 13 checks | pass |
| Schema isolation (`deploy/local/checks/schema-isolation.sql`) | 7 roles × all schemas/tables | 0 cross-schema privileges |

**Vector parity:** both engines reproduce all 84 vectors byte for byte. AMENDMENT-004 added P052–P054, so the count is 84, not the 81 the prompt expected.

**State machines:** every service test loads `transitions.json` and asserts that every invalid pair of its machines returns invalid-transition and every built transition passes (pool also asserts 501 for unbuilt ones). Over HTTP, in-scope invalid commands return their specified problem: already-member, active-commitment-exists (DB partial unique index), not-a-member, quote-not-final, deadline-passed (pool, quote, order — all DB clock), version-conflict, precondition-required, idempotency-key-missing/reused, capacity-reached, invalid-tiers, discount-exceeds-limit, quote-invalid, round-not-open, webhook-signature-invalid, deposit-not-captured path, and 501 for passkeys, re-round choice, quote revisions, order decline/cancel.

**Concurrency and deadlines:** 100 parallel joins (distinct buyers) → interested_count = 100 with 100 distinct aggregate versions; 20 parallel requests with one key → one membership, 19 replays; 100 parallel commits + 100 concurrent captures → committed_count = 100 and exactly one PoolReady; a commit that reached pool before the sealed deadline but committed after it → 409 deadline-passed with `server_time > deadline`, nothing stored.

**Authorisation:** the 42 P1 endpoints carry 106 authz test ids. `AuthzDenyChecks` runs the -D half for every P1 endpoint of each service (no token, forged key, expired, wrong audience → 401; client identity header → 400 (AZ-INT-03); wrong token kind or role → 403/404; internal endpoints need the right scope, checked before validation). -A and -X cases and the named tests are explicit: AZ-T1, T2, T3, T4, T5, AZ-INT-01, AZ-INT-03, AZ-INT-06, AZ-SVC-01/02/03, plus E033-X, E039, E067-X, E069-X, E081/E082-X, E088-X, E111-X, E112-X.

**Contracts:** every outbox row written in the pool, payment, quote, order and identity tests validates against its JSON Schema (envelope + payload).

**CI:** `.github/workflows/ci.yml` (path-filtered JVM matrix, Node/web checks, full E2E on every PR) plus `spec.yml`. First green PR run (PR #2): **8 min 27 s wall clock**. The longest job is e2e at **8 min 24 s**: jars about 2 min, images and stack about 2 min (healthy 43 s after `up`), Playwright 4.1 min. The ten JVM matrix jobs take 0.9–2.0 min each in parallel, node 43 s, spec checks 6–9 s. **Runner memory headroom** during e2e (GitHub ubuntu-latest, 16 GB): 5.0 GiB used, 10.7 GiB available, so the stack fits with room to spare.

The first PR run failed on two CI-only issues, now fixed: `gradlew` lost its executable bit when committed from Windows, and the Razorpay fake read `WEBHOOK_URL` at import time, before its contract test set it.

## 5. Measurements
Docker Desktop on Windows 11, WSL2 VM capped at 10 GB, 20 logical CPUs. JVMs: Temurin 21, SerialGC, `MaxRAMPercentage=70` of a 420 MiB container limit, OTel Java agent attached.

Memory per container (`docker stats`): idle = stack up, before any journey; journey peak = maximum over 34 samples (every 5 s) while two browser journeys ran in parallel.

| Container | Idle (MiB) | Journey peak (MiB) | Limit (MiB) |
|---|---|---|---|
| gateway | 253 | 309 | 420 |
| identity | 260 | 266 | 420 |
| catalog | 250 | 250 | 420 |
| dealer | 246 | 247 | 420 |
| pool | 257 | 271 | 420 |
| quote | 256 | 267 | 420 |
| order | 258 | 271 | 420 |
| payment | 256 | 268 | 420 |
| postgres | 130 | 142 | 512 |
| localstack | 193 | 156 | 768 |
| web (Next.js standalone) | 50 | 62 | 320 |
| otel-collector | 56 | 77 | 160 |
| jaeger | 40 | 96 | 256 |
| redis | 7 | 12 | 96 |
| mailpit | 8 | 17 | 64 |
| razorpay-fake | 13 | 16 | 96 |
| **Total** | **2,533** | **2,717** (highest single-sample total) | |

The eight JVMs account for 2,036 MiB idle and 2,149 MiB at peak (about 250–310 MiB each, including the OpenTelemetry agent).

**Cold starts** (all eight JVMs starting at the same time, so contended; Spring "Started … in", then JVM total):

| Service | Spring started | Process total |
|---|---|---|
| dealer | 23.9 s | 36.6 s |
| catalog | 24.5 s | 37.2 s |
| identity | 27.1 s | 39.9 s |
| gateway | 28.5 s | 41.4 s |
| quote | 35.5 s | 48.5 s |
| payment | 35.7 s | 48.9 s |
| order | 36.6 s | 49.3 s |
| pool | 37.5 s | 50.3 s |

Whole stack from `docker compose up` (images built) to every service healthy and the web answering: 58–70 s.

**Journey duration (demo mode):** sealed round 90 s, final round 0 s, decision 60 s, confirmation 60 s, sweepers every 5 s. API journey 230–234 s; browser journey 251 s at each width (sign-in to voucher, both in parallel).

**Lite-profile input for P6:** the whole stack peaked at about 2.7 GiB of container memory, well under 4 GB. That suggests t4g.medium (4 GB) can hold it, contrary to P0 risk 5, but the JVMs here use small heaps under a 420 MiB container limit and the host OS, Docker and page cache are not counted. Confirm on the real instance in P6 before choosing; t4g.large stays the safe option.

## 6. Bugs found and fixed
1. **Transition table lost transitions** (found when quote loaded its table): two transitions can share a (from, to) pair (QUO-T09 and QUO-T10 are both DRAFT → EXPIRED); the map kept the last id, so a built transition looked unknown. Now a set of ids per pair.
2. **Same aggregate version on two events** in one pool transaction (RoundClosed + FinalRoundOpened, RoundClosed + DecisionOpened; also order's resolution events): a MONOTONIC consumer of both would drop the second as stale. One version per event now.
3. **Duplicate quote create returned 500**: the unique-index violation aborted the Postgres transaction and the follow-up lookup failed. Found by QuoteIT; existing quote is checked first.
4. **Validation before authorisation** on internal endpoints: a caller without the scope got 400 (and learned the request shape). Found by the authz matrix; `@RequiresScope` is enforced by an interceptor before binding.
5. **Missing query parameter** rendered Spring's default error, not problem+json. Found by the authz matrix.
6. **Join status disappeared** after joining (the block holding it hid once a membership existed). Found by Playwright.
7. **First deposit call after a cold start failed** in pool (`application/octet-stream` while payment had answered 200 JSON). Not reproducible afterwards; the call is idempotent by the deposit key, so pool now retries it up to 3 times. Found by Playwright.
8. **Pool page crashed for committed buyers when offers arrived**: `<Button asChild>` rendered a `null` spinner slot beside the link, and Radix Slot requires exactly one child (React counts `null`). Found by Playwright; asChild now passes its child through, with a Storybook story.
9. **Outcome messages vanished** after confirm and leave (same pattern as join: the status lived inside a block that hides once the state changes). Found by Playwright.
10. **The web tier never joined backend traces**: no async context manager was registered in the BFF, so `traceparent` was never injected. Found by the Jaeger assertion in the E2E; the span is now put into the context explicitly.
11. Test-harness bugs found on the way (not product bugs): two workers reading each other's OTP mail (now one dealer account per project), no navigation timeout, polling with page reloads that only ever saw the loading skeleton, ambiguous locators.
12. Startup issues: config bean cycles (static `@Bean`), YAML placeholders inside flow maps, gateway key volume owned by root, `search_path` in tests (explicit `hikari.schema`), the isolation check placed in the Postgres init directory, host `node_modules` copied into the web image.

The 100-parallel-join test passed first time; the counts were never wrong.

## 7. Gaps and risks for P2
- **Unbuilt transitions return 501** (stated plainly): re-round (POOL-T09/T10, MEM-T12…T16, E042), NO_QUOTES/EXPIRED/CANCELLED paths (POOL-T04/T06/T11/T12/T13), late capture (MEM-T06, PAY-T05/T10 — a late capture currently dead-letters), quote revise/withdraw/cancel/void, order decline/cancel and every booking step after dealer confirm, delivery release (PAY-T09/T11), refund failure handling (REF-T04+), passkeys.
- **The pre-commit copy promises cancel-after-confirm**, which returns 501 in P1.
- **Saga steps** only alarm when late; no retry/compensation yet. `StockUnavailable` is not consumed.
- **Gateway** does not consume SessionRevoked/StaffRevoked yet; revocation relies on the 30 s cache TTL.
- **No realtime**: pages poll every 10 s.
- **One synthetic dealer**, one city (Pune); Hector and Dzire/Ertiga/Creta show "no estimate" because fuel or displacement isn't published.
- **Typed client** is only as precise as springdoc's view: most responses are `Map`, so response types are loose (request bodies are typed).
- **Idempotency** response stored after commit (see §3.5).
- **Memory**: 2.7 GiB peak for one or two concurrent journeys; JVM heaps are capped small (SerialGC, 70 % of 420 MiB), so load tests (P6) may need larger limits.
- **Hindi** strings are not written; only the en scaffolding.
- **The manual run against real Razorpay test mode has not been done** (needs your keys).

## 8. Your actions needed
1. **Razorpay test mode**: create test keys and a webhook secret; put them in `.env` (`RAZORPAY_BASE_URL=https://api.razorpay.com`, `RAZORPAY_KEY_ID=rzp_test_…`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAY_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js`). Then start a free tunnel (`cloudflared tunnel --url http://localhost:8080`) and register `<tunnel>/webhooks/razorpay` for `payment.authorized`, `payment.captured`, `payment.failed`, `refund.processed`, `refund.failed`. I'll run the journey once and record it.
2. **Review**: Q-042 (pool minimum), Q-041 (success fee), and the SPEC.md §6 contract additions.
3. **Merge PR** once CI is green.
