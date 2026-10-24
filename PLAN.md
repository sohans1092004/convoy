# Convoy: Group Car Buying for India (Project Plan v3)

> v3 adds an external review to v2 (microservices, 50,000 users). The main changes are: business rules frozen before any code, a fix for circular tier pricing, a canonical pricing and savings model, a full deposit lifecycle, four trust features, precise messaging contracts, per-profile AWS paths, measurable frontend quality, and the first complete buyer journey moved early.
> Working name: **Convoy**. Goal: a real end-user product whose frontend is as strong as the backend work in Concord, Reliant and Sentry-RCA.

---

## 1. Product

Buying a new car in India is a one-on-one negotiation that the buyer usually loses. **Convoy lets buyers who want the same model in the same city group up, so verified dealers compete for the whole group with sealed, itemised quotes.** Each buyer still decides alone.

In 2025, JITO members bought about 186 luxury cars through group negotiation, with a reported collective saving of about ₹21 crore ([WION](https://www.wionews.com/business-economy/how-the-jain-community-s-bulk-buying-saved-rs-21-crore-on-186-cars-1760942098618/amp)). Convoy's test is whether *ordinary* buyers can do this with less uncertainty and effort.

### Roles
| Role | Main jobs |
|---|---|
| Buyer | Discover and save cars, get pool suggestions, join and commit, compare quotes, ask structured questions, accept, confirm, track the order, raise a dispute, review the dealer |
| Dealer staff | Request inbox, build and revise quotes, declare stock, answer clarifications, see rank in the final round, confirm bookings and deliveries |
| Dealer admin | Dealership profile, GSTIN, staff (invite/revoke), analytics |
| Platform admin | Dealer verification, disputes and refunds, catalogue and pricing rules, audit review, marketplace metrics |

### Honest scope (also stated in the README)
- No real dealers. Dealers are seeded synthetic dealerships plus a **dealer simulator** that behaves like a market participant: a stock-limited inventory, a margin floor, and strategies (aggressive, conservative, slow responder, withdraws, misprices an add-on), with every behaviour documented.
- Payments run in **Razorpay test mode**. Every payment screen says "TEST: no real money", and test transactions are never presented as real funds.
- Catalogue: about 30 models across 8 brands, with ex-showroom prices hand-collected from public sources, each with a source and date. RTO and insurance figures are labelled **estimates**.
- Any marketplace metric from synthetic data is labelled **simulated**. A small real usability beta (20–50 people, mock pools) reports its sample size and dates.
- A real commercial launch would need legal review (consumer protection, competition law, dealer contracts, payments, DPDP). The design flags this; it does not resolve it.

---

## 2. Business rules (frozen in P0, before any code)

### 2.1 Pool state machine
`FORMING → READY_FOR_QUOTES → QUOTE_OPEN → FINAL_ROUND → DECISION_OPEN → CONFIRMATION → BOOKING → COMPLETED`

Exits: `EXPIRED` (minimum not reached by the forming deadline), `NO_QUOTES` (fewer than 1 valid quote), `CANCELLED` (admin action, with a reason).

| Transition | Trigger (who) | Guard | Emits | Repeat / late / crash |
|---|---|---|---|---|
| FORMING → READY_FOR_QUOTES | System, when committed members ≥ minimum | `state=FORMING AND version=v` | `PoolReady` | Repeat: no-op (version mismatch). Crash before commit: nothing happened. Crash after commit: outbox still publishes |
| READY → QUOTE_OPEN | System; dealers invited | Eligible dealers ≥ 1 | `QuoteRoundOpened(deadline)` | Same pattern |
| QUOTE_OPEN → FINAL_ROUND | Deadline | `now_db() ≥ round_deadline` | `RoundClosed` | The scheduler is only a *wake-up*. The owning service checks the deadline against its own data and DB server time. A sweeper runs every minute as a backstop |
| FINAL_ROUND → DECISION_OPEN | Deadline | Same | `DecisionOpened` | Same |
| DECISION_OPEN → CONFIRMATION | Deadline | Same; acceptances frozen | `AcceptancesFrozen` | Same |
| CONFIRMATION → BOOKING | Confirmation deadline + tier resolution | §2.2 | `TierResolved`, `BookingsIssued` | Saga step, persisted |
| BOOKING → COMPLETED | Every booking delivered, cancelled or disputed-and-closed | — | `PoolCompleted` | — |

Every transition is documented with: who may trigger it, required state, transaction and expected version, event, idempotent repeat, late arrival, crash at each point, and timeout/recovery. **Quote** (`DRAFT → SUBMITTED → REVISED* → FINAL → EXPIRED | WITHDRAWN | VOID`) and **order** state machines get the same tables.

**Deadline rule (exact):** a request is accepted only if the owning service's transaction reads `now() < deadline` from the database clock **at commit time**. Arrival time at the gateway doesn't count, so a request queued before the deadline but committed after it is rejected with `409 DEADLINE_PASSED`. The UI explains this and shows the server-time countdown.

### 2.2 Tiered pricing without circularity: "confirm at the ceiling; the price can only fall"
1. Each quote has tiers, for example 1–4 buyers ₹X, 5–9 ₹X−45k, 10+ ₹X−60k, plus a **minimum fulfilment count** the dealer commits to honour (it may be 1).
2. **Accept** (DECISION_OPEN) is non-binding interest. Live counts are shown.
3. **Confirmation**: each accepting buyer receives an exact **ceiling price**, which is the price at the *lowest tier the quote allows at or above its minimum*, and a confirmation deadline. Confirming means "I will buy at this price or lower."
4. **The tier count is defined as confirmed buyers at the confirmation deadline** (confirmation recorded and deposit captured). It is *not* acceptances and *not* completed bookings.
5. **Resolution**: tier = f(confirmed count). Every confirmed buyer pays the resolved tier price, which is always ≤ their ceiling. Nobody is surprised upward.
6. If confirmed count < minimum fulfilment, the quote is **VOID**, every affected deposit is refunded automatically, and buyers are told why. The pool may open one new round if it is still within its purchase window.
7. Cancellations after booking do **not** change the price for the others; the dealer accepted that risk when it set its tiers. This is shown to dealers in the quote builder.

### 2.3 Deposit contract (₹999 configurable, test mode)
- **Flow:** commit → create Razorpay order (idempotency key = buyer + pool) → checkout → status `PENDING`. **Only the signed `payment.captured` webhook makes it `CAPTURED`.** The browser callback only shows "pending".
- **Capture:** automatic capture (pools last longer than an authorisation would hold).
- **Payment states:** `INITIATED → PENDING → CAPTURED | FAILED`.
- **Refund states:** `REFUND_REQUESTED → REFUND_INITIATED → REFUNDED | REFUND_FAILED`. A failed refund is retried with backoff, then alerts and appears in the admin queue.
- **Applying to a booking:** `CAPTURED → APPLIED_TO_BOOKING`.

| Situation | Outcome |
|---|---|
| Buyer leaves before CONFIRMATION | Full refund, no conditions |
| Pool EXPIRED / NO_QUOTES / CANCELLED, quote VOID or WITHDRAWN, stock unavailable, dealer cancels or doesn't honour the price | Full refund, automatic, with reason code |
| Buyer misses the confirmation deadline | Treated as declined → full refund |
| Buyer cancels after confirming (test mode) | Full refund, recorded as a buyer cancellation (affects nothing else; the rule is shown up front) |
| `payment.captured` arrives after the buyer left or the pool closed | Auto-refund, reason `LATE_CAPTURE` |
| Duplicate request / duplicate webhook | Idempotency key / event-ID dedupe → no second effect |

**Ledger invariants:**
- Every journal entry balances debits and credits.
- Posted entries are immutable; refunds are compensating entries.
- Each deposit has exactly one terminal outcome (applied or refunded).
- Nightly reconciliation against Razorpay flags mismatches and stuck webhooks.

### 2.4 Canonical price model (one engine, used everywhere)
A pure, versioned function used by the catalogue, the quote builder, comparison, acceptance and confirmation. Each calculation stores **inputs, ruleset version, timestamp, output and a hash**, so a quote accepted under yesterday's rules can still be explained after a catalogue update.

| Component | Treatment |
|---|---|
| Ex-showroom | Exact variant and city, with source and effective date |
| Dealer discount | Explicit amount and the base it applies to |
| Registration / RTO | Estimate from state rules (vehicle category, fuel, price band); labelled as an estimate unless dealer-confirmed |
| Insurance | Standard comparable cover (comprehensive, year-1 own damage plus 3-year third party) as the baseline; add-ons listed separately as optional |
| TCS | 1% on vehicles above ₹10 lakh (Income-tax Act 2025, s.394, formerly s.206C(1F); [rate chart](https://studycafe.in/tcs-rate-chart-as-per-income-tax-act-2025-for-tax-year-2026-27-419612.html)), re-verified in P0. Shown separately: it's cash paid now, but generally claimable as credit against the buyer's income tax |
| FASTag and fees | Itemised, with basis |
| Accessories | Each marked **optional** or **dealer-required**. Required items are flagged for the buyer |
| Extended warranty | Optional, separate |
| Delivery and other charges | Itemised; known-questionable charges (e.g. "handling") flagged for the buyer to query |

**Two headline figures:** **cash payable at purchase** and **estimated net cost** (cash payable minus creditable TCS, with an explanation). There's never one opaque "effective price".

**Rank in the final round** (published identically to all dealers): cash payable for the pool's reference configuration with mandatory items only and baseline insurance, computed per variant. Ties go to the earliest delivery date, then the earliest submission. A dealer sees only its own rank per variant, never competitors' values.

### 2.5 Savings: what we're allowed to claim
Four separate labels, never merged:
1. **Dealer-stated discount.**
2. **Difference vs. reference price:** the dated catalogue estimate for the same configuration.
3. **Difference vs. the best other quote** received in the same pool.
4. **Realised saving:** only if the buyer confirms the final invoice amount (optional upload or entry).

"₹X saved" is shown only for (3) or (4), with the comparison named.

### 2.6 Cancellation rules (shown in the UI *before* commitment)
| Case | Rule |
|---|---|
| Buyer leaves after committing | Free until CONFIRMATION; refund |
| Dealer withdraws a quote | Allowed until the round deadline (audited); after FINAL, withdrawal = dealer cancellation |
| Dealer doesn't honour the final price, or stock vanishes | Buyer refunded, booking cancelled, dispute auto-opened, dealer reliability score hit, admin review |
| Buyer misses the confirmation deadline | Declined; refund |
| Pool doesn't reach its minimum | EXPIRED; refunds; buyers offered similar pools |
| Booked but never delivered by the promised date + grace period | Buyer prompted to open a dispute; admin escalation path |

---

## 3. Requirements

### 3.1 Functional (P0 = needed for the MVP, P1 = high value)
1. **Auth (P0):** email OTP + passkeys; buyer and dealer onboarding; roles; dealer staff invite and revoke.
2. **Catalogue and price calculator (P0):** §2.4 engine, variant comparison, source dates.
3. **Saved cars, preferences and pool matching (P0):** save models; set city, intended purchase date, acceptable variants, colours, fuel and transmission; get matching pool suggestions with honest status ("accepting members", "awaiting quotes", "closes in 2 days").
4. **Pools and commitment (P0):** §2.1–2.3; interested vs. committed counts shown separately to everyone.
5. **Quotes with versioning and stock (P0):**
   - quote states, auditable revisions, "last updated" visible to buyers
   - stock claims per variant and colour, with availability date and dealer-confirmed flag
   - the quote must name the exact variants its price and stock cover. A discount on one variant never implies others
6. **Quote transparency / trust centre (P0):**
   - itemised breakdown, validity, stock, mandatory vs. optional, insurance assumptions, source and date per figure
   - a **rule-based evidence indicator** per line (dealer-confirmed / catalogue estimate / unverified), not an AI score
7. **Decision, confirmation, tier resolution (P0):** §2.2.
8. **Order tracking (P0):** a persistent timeline (accepted → confirmation pending → deposit outcome → booking issued → dealer confirmed → delivery scheduled → delivered / cancelled), voucher with QR verification, and a clear next action at every step.
9. **Disputes and support (P0):** cases with category, evidence attachments (S3), status, owner and resolution history; refunds and cancellations carry explicit reasons.
10. **Reviews (P0):** only buyers with a delivered booking can review that dealer.
11. **Structured clarifications (P1):** buyers ask about a specific quote from a fixed set of question types plus short free text; the dealer's answer is visible to that pool's buyers. No contact details are exchanged before consent, and no general chat.
12. **Marketplace analytics (P1):** fill rate, time to fill, dealer response rate, pools with ≥2 valid quotes, acceptance→confirmation→delivery conversion, cancellation rate, savings by the §2.5 labels (simulated data labelled).
13. **Notifications (P0 in-app and email; P1 web push).**
14. **Admin console (P0 verification, disputes, refunds queue, audit; P1 catalogue editor, analytics).**

**Deliberately excluded:** AI recommendations or chat assistant, native apps, live open auctions, loans and insurance sales, used cars and servicing, separate analytics/search/recommendation services, blockchain receipts, ML fraud detection.

### 3.2 Capacity model
| Quantity | Estimate | Basis |
|---|---|---|
| Registered buyers | 50,000 | Target |
| Dealerships / staff | ~500 / ~2,000 | ~8 metros × 8 brands × several dealers |
| DAU | ~5,000 normal; ~20,000 at the festive peak | 10% / 40% |
| Concurrent users | ~10,000 | Festive evening |
| Pools created | ~5,000/yr → ~100k quote and line-item rows/yr | ~10% of buyers start a pool; ~20 line items × ~1 quote per dealer per round |
| Edge request rate | ~1,000 req/s before CDN | 1 req per 10 s per concurrent user |
| SSE connections | ~10,000 | One per open pool or dealer screen |
| Deadline burst | 1,000 quote submissions in 120 s | 50 pools × 20 dealers; **average 8.3/s, but tested as synchronised arrivals in the final 5 s** |
| Notification burst | 100,000 in 30 min | **Average 55.6/s**; measured by oldest-message age, delivery latency, retries, DLQ growth, drain time |

### 3.3 Non-functional targets: every one measured and reported, including misses
| Area | Target and measurement method |
|---|---|
| API latency | p95 < 300 ms, p99 < 800 ms on **named routes** (pool read, join, quote submit, comparison), measured at the gateway under workload W9 (§8) |
| SSE | 10,000 connections held for 30 min; reconnect storm of 10,000 recovers to snapshot-consistent state; slow consumers are disconnected, not buffered forever |
| Deadline | 0 quotes committed after the deadline; 0 quotes lost that committed before it (rule in §2.1) |
| Events | 0 lost (outbox), 0 duplicate business effects (inbox), stale events ignored by aggregate version |
| Isolation | notification, realtime or audit down → join, quote, confirm and pay still work |
| Ledger | Balances after every test; every deposit has exactly one terminal outcome |
| Frontend | p75 **LCP < 2.5 s, INP < 200 ms, CLS < 0.1** from real-user web-vitals; lab runs on an emulated mid-range Android over 4G via Lighthouse CI as a regression gate; **route-level initial JS budgets** (compressed, first load) enforced in CI from the measured baseline |
| Accessibility | WCAG 2.2 AA; axe in CI; manual keyboard and screen-reader pass for the 5 flagship screens |

---

## 4. Architecture (microservices, built in waves)

### 4.1 Services and build waves
All 11 boundaries are designed and owned from day one. Implementation comes in waves so the first demo doesn't wait for peripheral services.

| Wave | Services | Why |
|---|---|---|
| **1** | gateway, identity, catalog, pool, dealer, quote, order, payment | The core buying journey |
| **2** | realtime, notification | Independent scaling and failure-isolation demonstrations (polling fallback until then) |
| **3** | audit (dedicated consumer), admin read models, analytics views | After the core lifecycle works |

Justification (ADR-001): different load shapes (realtime on connections, catalog on reads, quote bursty at deadlines, notification on queue depth) and isolation of payment. Costs accepted: network failures, eventual consistency, operational load. The ADR states that a modular monolith would also serve 50,000 users.

### 4.2 Data ownership (enforced, not just intended)
- One shared PostgreSQL cluster (data volume doesn't need more). Each service has **its own schema and DB user**, and no grants on other schemas (checked by a CI test that queries the catalogue).
- Migrations are owned per service (Flyway). Each service has its own pool limits. **No distributed transactions** across services; cross-service consistency comes only from sagas and events.
- **Redis is never a source of truth.** It holds only rate limits, caches, idempotency fast-paths (always backed by a DB unique key) and pub/sub fan-out. Flushing Redis must not corrupt membership, payment or booking state, and a drill proves it.

### 4.3 Event contract
**Envelope:** `event_id, event_type, schema_version, aggregate_id, aggregate_version, occurred_at, correlation_id, causation_id, producer, payload`.

1. **Outbox:** written in the **same local transaction** as the state change. A relay publishes to SNS and marks rows delivered **only after the broker acknowledges**, so a crash between publish and mark causes a duplicate, which inbox dedupe absorbs.
2. **Inbox:** a unique `event_id` per consumer. The business effect and the inbox row commit together, and the SQS message is acknowledged afterwards.
3. **Ordering:**
   - FIFO queues (MessageGroupId = aggregate ID) only where order matters (pool membership, round state).
   - FIFO keeps *enqueue* order but cannot fix events a producer emits out of order, so consumers also check `aggregate_version`.
   - Gap → park the event and retry; stale → ignore and count it; still missing after a timeout → fetch a snapshot from the owner via API.
4. **Poison messages:** maxReceiveCount → DLQ with alarm, plus a redrive runbook.
5. **Required failure tests:**
   1. commit then crash before publish
   2. broker accepted, relay crashed before marking delivered
   3. consumer committed, crashed before acknowledging
   4. two events for the same pool out of order
   5. a poison message
   6. a consumer offline for hours, then replaying its backlog

### 4.4 Authentication contract
- **Browser ↔ web (BFF):** opaque session ID in an HttpOnly, Secure, SameSite=Lax cookie. Identity stores sessions (hashed).
- **web → gateway:** the session ID is forwarded in a header that the BFF strips from any browser input. The gateway accepts traffic only from web (security group) and the realtime route.
- **gateway:** validates the session with identity (cached ≤ 30 s, revocation event clears the cache), then mints an **internal JWT**:
  - claims: `iss=convoy-gateway`, `aud=<target service>`, `sub`, roles, dealership ID, `exp` = 2 min
  - signed ES256, published at a JWKS endpoint; keys rotate with overlap
- **Services:**
  - accept only that JWT
  - reject caller-supplied identity headers
  - enforce resource-level authorisation themselves
  - service-to-service system calls use **service tokens** (client credentials from identity, with per-service audience and scopes)
- **Revocation:** dealer staff revocation takes effect within the session-cache window (≤ 30 s), measured by a test.

### 4.5 Realtime (SSE) recovery design
- Every pushed message carries the **aggregate version**. Heartbeat every 15 s.
- One bounded queue per connection; a slow consumer is dropped and must reconnect.
- On reconnect the client calls the owner's **snapshot endpoint**, then resumes the stream. Redis Pub/Sub has no replay, so correctness never depends on it.
- If realtime is unavailable, the UI falls back to polling the snapshot every 10 s.

### 4.6 Audit provenance
- Audit is fed by every service's outbox.
- The audit table is append-only: the application role has INSERT/SELECT only, and an update/delete trigger raises an error. Each row has a **hash chain** (each row includes the previous row's hash) so tampering is detectable.
- Rows record the actor, correlation ID and a sensitive-access flag.
- Audit can't prove a producer never *omitted* an event, so outbox lag, and reconciliation between owners' state and audit counts, are monitored as well.

### 4.7 Topology per deployment profile (internally consistent)
| Profile | Traffic path | Runs | Claims it supports |
|---|---|---|---|
| **local** | Browser → web → gateway → services (Docker Compose) + LocalStack SNS/SQS/S3 | Reduced replicas | Development only |
| **lite (demo, always on)** | Browser → CloudFront → **EC2 (Caddy)** → web / gateway containers. **No ALB, no WAF** | All services, single host, container memory limits and tuned JVMs (heap limits, SerialGC, CDS); real SNS/SQS/S3/SES; Postgres and Redis on the host | A working public demo. **No availability or scale claims.** Instance size chosen **after measuring** idle and peak memory in P6; starts at 4 GB and moves to 8 GB if measurements need it |
| **full (on demand)** | Browser → CloudFront → WAF → ALB → ECS Fargate (Service Connect) | 2 AZs, ≥2 tasks per service, autoscaling, RDS Multi-AZ, ElastiCache, ADOT/X-Ray | All capacity, failover and isolation claims |

### 4.8 Security, privacy and integrity
**Automated authorisation tests (in CI), each one a test case:**
1. A dealer can't enumerate pool members.
2. A dealer can't get a buyer's contact details from a quote ID.
3. Accepting dealer A's quote reveals nothing to dealer B.
4. A buyer can't read another buyer's commitment or payment.
5. Staff can't read another dealership's quotes or analytics.
6. Revoked staff lose access within 30 s.
7. Admin reads of sensitive data are audited.

**DPDP (phased):** the Rules were notified on 14 Nov 2025, and provisions start on different dates. The plan doesn't claim they all apply at once. Product capabilities:
- privacy notice per purpose; separate consent records per purpose, with history
- data minimisation, and dealer disclosure limited to the stated purpose
- export and deletion, tracked across services until every service confirms
- **retention per category**: payments, invoices, disputes and audit are kept for their required periods rather than deleted on request (documented exceptions)
- incident response runbook; log of access to sensitive data

**Marketplace integrity:**
| Risk | Control |
|---|---|
| Fake or duplicate buyers | OTP rate limits, one active commitment per model, device/IP heuristics for review |
| Dealers inflating demand | Verified dealers; staff accounts can't join pools; links between dealer staff and buyer accounts flagged |
| Fake discounts | §2.5 baselines; quoted vs. final price recorded |
| Quote leakage | Service-side access checks, audited reads |
| Collusion / misleading quotes | Anomaly flags (e.g. identical quotes across dealers) for admin review. A flag is never treated as proof |
| Payment replay | HMAC verification, idempotency, event dedupe |
| Fake reviews | Only verified-delivery buyers can review |
| Admin abuse | Least privilege, audit of sensitive actions, refunds above a threshold need a second admin |

---

## 5. Frontend (judged as product engineering)

### 5.1 The five flagship buyer screens
1. **Discover and compare:** model cards, variant comparison (no back-and-forth), on-road estimates with source dates, saved cars; filters usable on mobile.
2. **Join a pool:** minimum size, interested vs. committed counts, accepted variants, deadline, deposit terms, and what happens if the target is missed, all *before* the commit button.
3. **Compare offers:** sortable dealer cards, expandable itemised breakdown, mandatory vs. optional, evidence indicator per line, tier ladder with "price can only fall", stock confidence, delivery. Cards on mobile, never a squeezed desktop table.
4. **Accept and confirm:** ceiling price, deposit status, tier status, deadline, cancellation terms; explicit pending/failed states, never a bare spinner.
5. **Track the order:** timeline, QR voucher, dealer confirmation, delivery date, dispute and support actions, invoice confirmation; the next action is always obvious.

### 5.2 Interaction-state spec (required per flagship screen)
Each flagship screen must specify: **loading** (skeletons, stable layout), **empty**, **error** (recoverable, no lost input), **stale** (outdated prices, quotes or counts marked), **pending**, **conflict** (e.g. "the round closed while you were editing"), **offline**, **success** (what changed and what's next).

Example: double-clicking "Join pool" sends one idempotency key, shows one membership and one count increment, and reconciles the optimistic state with the server response.

### 5.3 Dealer quote builder (the second flagship)
- A persistent summary that updates live through the shared price engine (compiled to TypeScript from the same spec, with cross-checked test vectors).
- Validation of missing or inconsistent items; mandatory vs. optional made explicit.
- A tier editor with a "what if 3 / 7 / 12 buyers confirm" preview.
- A confirmation dialog, then an immutable submitted version; revisions are diffed and audited.
- Validity and deadline countdowns; the published ranking rule shown beside the rank.
- Keyboard-navigable, accessible dense tables.

### 5.4 Design-system quality gate
Every important component has documented variants, sizes and states in Storybook; keyboard and screen-reader behaviour; light and dark themes; visual regression tests; responsive checks; tokens only (lint rule against raw colours and spacing); and a written process for adding a new component.

### 5.5 Stack habits
Server Components by default. Charts and heavy widgets load only on routes that need them. TanStack Query for client state. Hindi and English, with lakh/crore formatting. PWA with an offline page (web push in P1). Tests: Vitest + RTL, Playwright (including a mobile viewport and a slow network), axe, Storybook interaction tests.

---

## 6. AWS and cost

**Zero-cost rule, restated:** development and the default demo workflow have **no recurring spend beyond the lite profile, which is budgeted**. Every full-profile run is a **planned, budgeted experiment**. The high-availability stack is never described as free. New accounts get up to $200 in credits for up to 6 months, but credits don't guarantee any particular architecture is free ([AWS](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/)).

**Cost table (filled in during P0 from the AWS Pricing Calculator, then updated with actual bills):**

| Area | What's recorded |
|---|---|
| Lite, always on | Monthly compute, EBS, data transfer, CloudWatch |
| Full-profile run | Hourly cost and total per representative run |
| NAT / ALB | Fixed hourly cost plus usage, including idle time |
| RDS | Instance, storage, backups, Multi-AZ premium |
| SNS/SQS | Request volume including retries and DLQs |
| Observability | Log ingestion and retention, traces, metrics |
| Traffic | CloudFront and origin transfer |

**Guardrails:**
- `terraform plan` reviewed before every apply.
- A maximum duration per full run, enforced by a scheduled destroy job.
- After every destroy: a **teardown verification** (tag-based resource inventory must be empty) and a cost report.
- A daily scheduled scan for unexpected resources.
- AWS Budgets alerts at low thresholds, plus a budget action.
- A written manual recovery runbook for interrupted applies or destroys.
- Load tests send email to a mock sink.

---

## 7. Tech stack (unchanged from v2 apart from the additions marked)

| Layer | Choice |
|---|---|
| Frontend | Next.js (App Router), React, TypeScript, Tailwind, shadcn/ui (Radix), TanStack Query, React Hook Form, Zod, next-intl, Recharts (lazy-loaded), Framer Motion (sparingly) |
| Frontend quality | Storybook + visual regression, Vitest, RTL, Playwright, axe-core, Lighthouse CI, web-vitals RUM, **per-route bundle budgets (new)** |
| Backend | Java 21, Spring Boot 3 (Web, Security, Data JPA + JdbcClient, Validation, Actuator), Spring Cloud Gateway, Resilience4j |
| Data | PostgreSQL 16 + Flyway (schema per service), Redis (non-authoritative) |
| Messaging | SNS → SQS (standard + selective FIFO), DLQs, outbox/inbox with the §4.3 envelope, EventBridge Scheduler (wake-ups only) |
| Contracts | OpenAPI 3 → generated TS client; JSON Schema events; consumer-driven contract tests |
| Shared logic | **Price engine spec + shared test vectors run against the Java and TypeScript implementations (new)**, the cross-language approach from Concord |
| Payments / email | Razorpay test mode, SES, Web Push (P1) |
| AWS | CloudFront, WAF, ALB, ECS Fargate, ECR, RDS, ElastiCache, SNS, SQS, EventBridge, S3, SES, Secrets Manager, KMS, CloudWatch, X-Ray, IAM, VPC; Terraform |
| CI/CD | GitHub Actions per service, OIDC to AWS, Trivy, ECS rolling deploys, teardown-verification jobs |
| Testing | JUnit 5, Testcontainers, LocalStack, Toxiproxy, k6 |

---

## 8. Load-test workloads (reproducible)
Each workload runs through warm-up, steady state, saturation and recovery. It records environment, resource limits, duration, dataset size and commit hash.

| ID | Workload |
|---|---|
| W1 | Catalogue reads (cache hit and miss mix) |
| W2 | Authenticated pool reads and membership mutations |
| W3 | Concurrent joins and leaves on **one** pool |
| W4 | Quote submissions with synchronised arrivals at the deadline |
| W5 | 10,000 SSE connections, fan-out, reconnect storm, slow consumers |
| W6 | Duplicate and delayed payment webhooks |
| W7 | Order saga under downstream failures (Toxiproxy) |
| W8 | Notification backlog: oldest-message age and drain time |
| W9 | Mixed peak traffic (the headline latency numbers) |

---

## 9. Phases (vertical slice first, then harden)

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0: Rules and spec** | Freeze §2 (state machines incl. invalid transitions, tiers, deposit, price, savings, cancellations), wireframes for the 5 buyer screens and the quote builder, event catalogue and ownership, ADRs, catalogue data with sources, cost estimates | Every transition has a full table; price engine test vectors written; wireframes cover all §5.2 states |
| **P1: Walking skeleton (the first convincing demo)** | Wave-1 services, thin: sign in → browse a model → join a pool → **one** dealer submits a quote → compare → accept → confirm → test payment → voucher. Small catalogue, local Compose, CI, tracing, design-system foundations | The full journey works end to end in Playwright, through real service boundaries and events |
| **P2: Pools hardened** | Matching and saved cars, counts under concurrency, version checks, outbox/inbox failure tests 1–6, realtime (wave 2) + snapshot recovery, optimistic UI with conflict states | W3 exact counts; all six messaging failure tests pass |
| **P3: Dealers and quotes** | Onboarding and verification, full quote builder, revisions, stock, sealed round + final round with the published ranking rule, deadline rule, dealer simulator | Deadline tests (synchronised arrivals) pass; no rank or sealed-value leaks in authorisation tests |
| **P4: Money and orders** | Tier resolution (§2.2), deposit lifecycle and ledger, reconciliation, order timeline, cancellations, disputes, reviews | Saga crash-recovery at every boundary; ledger invariants hold; webhook replay is harmless |
| **P5: Operational completeness** | Notifications (wave 2), audit (wave 3), clarifications (P1), DPDP export/delete/retention, admin console, analytics views | Deletion tracked to completion; authorisation tests 1–7 pass |
| **P6: AWS** | Terraform for lite and full, per-profile paths, OIDC deploys, guardrails, measure lite memory and pick the instance size | Lite live; full applied → verified → destroyed with an empty inventory and a cost report |
| **P7: Evidence** | W1–W9 on full; drills: kill each service, RDS failover, Redis flush, SQS backlog, poison message; frontend RUM and Lighthouse; usability beta | Results report with every target measured, misses included; postmortems |
| **P8: Release** | Polish the flagship screens, Storybook site, docs, demo video, interview guide, honest benchmark report and known limitations, résumé bullets | Ready to apply with |

---

## 10. Decisions (closed)
1. **SNS/SQS**, FIFO only where order matters; Kafka/MSK comparison kept in an ADR, not run.
2. **Model-level pools**; quotes must name exact variants for price and stock.
3. **Sealed round + one best-and-final round**; published ranking rule (§2.4).
4. **₹999 test deposit**, configurable; never presented as proof of real willingness to buy.
5. **Tier rule:** confirm at the ceiling, the tier is counted on confirmed buyers at the deadline, and the price can only fall (§2.2).

## 11. Evidence the project must produce (what goes on the résumé)
- An end-to-end journey video.
- Concurrency correctness on one pool, and a deadline correctness report.
- Ledger and webhook replay results; a saga crash-recovery report.
- The 10,000-SSE test with reconnect storm; W9 p95/p99.
- Frontend RUM (p75 LCP/INP/CLS), bundle budgets and an accessibility report.
- The full-profile cost report.
- Beta usability findings, plus simulated marketplace metrics labelled as simulated.

Résumé bullets are written only from measured results.
