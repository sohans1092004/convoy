# Convoy — frozen specification (P0)

**Status:** frozen at the end of P0 (2026-10-24). Source plan: [PLAN.md](PLAN.md) (v3, unedited). This file is the single entry point: every later phase builds and tests against the documents linked here.

## 1. Product summary
Convoy is a group car-buying marketplace for India. Buyers who want the same model in the same city form a **pool**. Verified dealers compete for the whole group with **sealed, itemised quotes**, followed by one best-and-final round. Each buyer still decides alone: they accept an offer (non-binding), then confirm at an exact **ceiling price** that can only fall as more buyers confirm. Deposits are ₹999 in **Razorpay test mode** ("TEST: no real money"). The product's promise is less uncertainty and less effort for ordinary buyers. Every price line carries a rule-based evidence level, and every saving claim names what it is compared against.

## 2. Scope and non-goals
**In scope (MVP, PLAN §3.1):**
- auth (email OTP + passkeys);
- catalogue + price calculator;
- saved cars, preferences and pool matching;
- pools and commitment;
- quotes with versioning and stock;
- trust centre / evidence;
- decision, confirmation and tier resolution;
- order tracking with QR voucher;
- disputes;
- reviews;
- notifications (in-app + email);
- admin console.

P1-priority additions: structured clarifications, marketplace analytics, web push.

**Honest limits:**
- no real dealers (synthetic dealerships + a documented simulator);
- test-mode payments only;
- about 30 models with dated, sourced prices; RTO and insurance are estimates;
- synthetic metrics are labelled "simulated";
- legal review (consumer protection, competition, dealer contracts, payments, DPDP) is flagged, not resolved.

**Non-goals (PLAN §3.1, deliberately excluded):** AI recommendations or chat, native apps, live open auctions, loans and insurance sales, used cars and servicing, separate analytics/search/recommendation services, blockchain receipts, ML fraud detection.

## 3. Frozen decisions (PLAN §10 + P0)
1. **SNS/SQS**, with FIFO only where order matters (pool events). The Kafka/MSK comparison is kept as an ADR, not run. ([ADR-002](docs/adr/ADR-002-sns-sqs-vs-kafka.md))
2. **Model-level pools**; quotes must name exact variants for price and stock.
3. **Sealed round + one best-and-final round**, with the published ranking rule. ([ranking-rule.md](docs/rules/ranking-rule.md), [ADR-008](docs/adr/ADR-008-ranking-rule.md))
4. **₹999 test deposit**, configurable; never presented as proof of real willingness to buy. ([deposit-contract.md](docs/rules/deposit-contract.md))
5. **Tier rule:** confirm at the ceiling, the tier is counted on confirmed buyers at the deadline, and the price can only fall. ([tier-resolution.md](docs/rules/tier-resolution.md), [ADR-007](docs/adr/ADR-007-tier-rule.md))
6. P0 additions, all consistent with PLAN:
   - deadline by owner DB clock at commit ([ADR-009](docs/adr/ADR-009-deadline-rule.md));
   - money as integer paise ([ADR-012](docs/adr/ADR-012-money-as-integer-paise.md));
   - one price spec with shared vectors ([ADR-011](docs/adr/ADR-011-one-price-spec-two-implementations.md));
   - Redis non-authoritative ([ADR-010](docs/adr/ADR-010-redis-non-authoritative.md));
   - the defaults in [OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md) (Q-001…Q-042), as amended below.

## 4. Document index
**Rules (business, frozen)**
- State machines as data: [README with Mermaid diagrams](docs/rules/state-machines/README.md) · [pool](docs/rules/state-machines/pool.yaml) · [membership](docs/rules/state-machines/membership.yaml) · [round](docs/rules/state-machines/round.yaml) · [quote](docs/rules/state-machines/quote.yaml) · [order](docs/rules/state-machines/order.yaml) · [booking](docs/rules/state-machines/booking.yaml) · [payment](docs/rules/state-machines/payment.yaml) · [refund](docs/rules/state-machines/refund.yaml) · [dispute](docs/rules/state-machines/dispute.yaml) · [shared patterns](docs/rules/state-machines/_patterns.yaml)
- [Tier resolution](docs/rules/tier-resolution.md) · [Deposit contract + ledger](docs/rules/deposit-contract.md) · [Pricing model](docs/rules/pricing-model.md) · [Savings model](docs/rules/savings-model.md) · [Cancellation rules + UI copy](docs/rules/cancellation-rules.md) · [Ranking rule](docs/rules/ranking-rule.md) · [Deadline rule](docs/rules/deadline-rule.md)

**Architecture**
- [Services and ownership](docs/architecture/services.md) · [Event catalogue](docs/architecture/event-catalogue.yaml) + [JSON Schemas](docs/architecture/events/envelope.schema.json) · [API outline](docs/architecture/api-outline.md) · [Problem types (RFC 9457)](docs/architecture/problem-types.yaml)
- [Auth contract](docs/architecture/auth-contract.md) · [Realtime recovery](docs/architecture/realtime-recovery.md) · [Deployment profiles](docs/architecture/deployment-profiles.md) · [Threat model](docs/architecture/threat-model.md)
- [ADRs 001–012](docs/adr/README.md)

**Testing (specified now, implemented later)**
- [Authorisation matrix](docs/testing/authz-matrix.md) · [Messaging failure tests](docs/testing/messaging-failure-tests.md) · [Load workloads W1–W9](docs/testing/load-workloads.md) · [Frontend quality](docs/testing/frontend-quality.md)
- Executable contract: [reference price engine](spec-tools/price_ref.py) · [vectors](spec-tools/vectors/pricing.json) ([tiers](spec-tools/vectors/tiers.json), [deposit outcomes](spec-tools/vectors/deposit-outcomes.json), [transitions](spec-tools/vectors/transitions.json)) · [validator](spec-tools/validate_spec.py)

**Data, cost, product, design**
- [Catalogue](docs/data/catalogue/models.yaml) + [sources and counts](docs/data/catalogue/SOURCES.md) · [TCS rule](docs/data/rules/tcs.yaml) · [RTO rules](docs/data/rules/rto.yaml) · [Insurance baseline](docs/data/rules/insurance-baseline.yaml) · [Ruleset metadata](docs/data/rules/ruleset.yaml)
- [AWS cost estimate](docs/cost/aws-cost-estimate.md) ([arithmetic](docs/cost/cost-arithmetic.md))
- [Personas and journeys](docs/product/personas-and-journeys.md) · [Metrics](docs/product/metrics.md) · [Glossary](docs/GLOSSARY.md) · [Open questions](docs/OPEN_QUESTIONS.md)
- [Wireframes index](design/wireframes/index.html)
- [P0 report](reports/P0_REPORT.md)

## 5. Tooling
```
python spec-tools/validate_spec.py      # all consistency checks (CI on every push)
python spec-tools/gen_vectors.py        # regenerate vectors (refuses if hand-verified totals disagree)
python spec-tools/render_machines.py    # regenerate the state-machine README
python spec-tools/gen_event_schemas.py  # regenerate event JSON Schemas from the catalogue
python spec-tools/crosscheck_yaml.py    # CI-only: miniyaml vs PyYAML parity
```
All tools are Python 3 standard library only, except the PyYAML cross-check in CI.

## 6. Change control
- After P0, **any change to a rule** needs either a new or superseding **ADR** (docs/adr) or an **`AMENDMENT-###`** entry below. This covers the state machines, tiers, deposit, pricing, savings, ranking, deadline, cancellation, event ownership, auth and anything in OPEN_QUESTIONS.
- Each entry gives: date, the rule changed, the reason, the affected files, the vectors added or changed, and the phase.
- A PR that changes a rule file without a matching ADR/amendment is rejected in review. `validate_spec.py` must pass on every PR.
- Data refreshes (new catalogue prices, a new ruleset version with re-verified sources) are not rule changes. They need a new `ruleset_version`, updated sources and regenerated vectors.
- PLAN.md is not edited after P0 except for typos, which are logged below.

### Amendments
All six were applied at the start of P1 (2026-10-24), before any application code. `validate_spec.py` passes with them.

**AMENDMENT-001 — second admin for every manual money action (replaces Q-040).**
- Rule: any manual refund, ledger adjustment or override needs approval by a second, different platform admin, whatever the amount. Automatic rule-driven refunds (expiry, VOID, late capture, re-round no-response, delivery release) need none.
- Reason: with ₹999 deposits, the P0 threshold of ₹5,000 would never apply.
- Files: refund.yaml (REF-T06), dispute.yaml (DSP-T05), deposit-contract.md, problem-types.yaml, threat-model.md, authz-matrix.md (AZ-INT-08), api-outline.md (E117), OPEN_QUESTIONS.md. Vectors: D21 side effect. Phase: P4 (admin), rule frozen now.

**AMENDMENT-002 — re-round keeps deposits by buyer choice (replaces Q-007).**
- Rule: when every quote is VOID and the pool is eligible for its single re-round, deposits are not refunded automatically. Each affected buyer has 48 h to keep the commitment (membership COMMITTED, no new payment) or take a refund; no response means refund. If the pool is not eligible, the automatic refund of PLAN §2.2 applies.
- Reason: making buyers pay again after a VOID adds friction (P0 risk 2).
- Files: membership.yaml (state REROUND_CHOICE, MEM-T12…T16; HANDED_TO_ORDER and RELEASED no longer terminal), pool.yaml (POOL-T09/T10), order.yaml (ORD-T06), event-catalogue.yaml (BookingsIssued, PoolReopened, MemberReleased, MemberCommitted), api-outline.md (E042), tier-resolution.md §5, deposit-contract.md §3. Vectors: D06 changed, D24–D27 added. Phase: P2 (re-round), rule frozen now.

**AMENDMENT-003 — the deposit is never paid to the dealer (clarifies Q-010, Q-026).**
- Rule: `APPLIED_TO_BOOKING` → `HELD_FOR_BOOKING`. After delivery the deposit is released (refunded in full) and ends `RELEASED`; `SETTLED` is removed. Every deposit ends `REFUNDED` or `RELEASED`, both full. The success fee is a separate receivable from the dealer (accounts 1300/4100, J-FEE); account 2300 is removed.
- Files: payment.yaml (PAY-T06, PAY-T09 rewritten, PAY-T11 added), refund.yaml (refund kind REFUND/RELEASE), order.yaml, event-catalogue.yaml (DepositHoldRequested, DepositHeld, DepositHoldRejected, DepositReleased), deposit-contract.md §5, GLOSSARY.md, OPEN_QUESTIONS.md (Q-041 added: fee amount). Vectors: D20 → RELEASED; ledger accounts. Phase: P1.

**AMENDMENT-004 — insurance used for ranking (tightens Q-031).**
- Rule: the dealer's insurance figure counts for ranking only if it declares exactly the baseline cover (IDV 95 %, no add-ons) and is marked binding; otherwise the baseline estimate is used. The quote builder shows this to the dealer.
- Files: price_ref.py (`rankable_insurance`), pricing-model.md, ranking-rule.md, insurance-baseline.yaml, quote-builder wireframe. Vectors: P029 input now declares binding baseline cover (total unchanged); P052–P054 added. Phase: P1 engines, P3 builder.

**AMENDMENT-005 — ceiling plus a labelled projection (extends Q-034).**
- Rule: ranking stays at the ceiling. The buyer comparison shows the ceiling and "If the count stays at N: ₹X", labelled "not guaranteed". N = buyers accepting the offer during DECISION_OPEN, confirmed buyers during CONFIRMATION.
- Files: ranking-rule.md §4, compare wireframe. Vectors: none (projection = existing tier resolution). Phase: P1.

**AMENDMENT-006 — SNS FIFO to SQS standard queues (closes P0 risk 6).**
- Rule: ordering consumers (quote, order, realtime; payment for membership events) use FIFO queues on pool-events.fifo; notification and audit use standard queues. Source: [AWS, 2023-09-14](https://aws.amazon.com/about-aws/whats-new/2023/09/amazon-sns-fifo-topics-message-delivery-sqs-standard-queues/), accessed 2026-10-24.
- Files: event-catalogue.yaml header, ADR-002. LocalStack behaviour: recorded in reports/P1_REPORT.md. Phase: P1.

**AMENDMENT-007 — Pool minimum and success fee set by the owner (closes Q-041, Q-042).**
- Rule (Q-042): `min_members` = 5 in production; the local demo stack uses 1 so one test buyer can run the journey (`POOL_MIN_MEMBERS`).
- Rule (Q-041): the success fee is a flat ₹2,500 (250000 paise) per *delivered* booking, recorded as a dealer receivable (J-FEE, Dr 1300 / Cr 4100) inside PAY-T09. It is configurable (`SUCCESS_FEE_PAISE`, 0 = off) and never taken from a buyer deposit.
- Source: the owner's Phase 2 instructions (2026-10-24). Files: OPEN_QUESTIONS.md, deposit-contract.md §5, pool and payment application.yml. Phase: P2.

**AMENDMENT-008 — Rejoin, admin passkeys, revocation ends sessions (closes Q-045, Q-043, Q-044).**
- Rule (Q-045): a buyer who left may rejoin the same pool while it is FORMING, READY_FOR_QUOTES, or QUOTE_OPEN before the sealed-round deadline (DB clock). At most 2 leaves per buyer per pool: after the second leave a rejoin is refused (`rejoin-limit-reached`, 409); leaving itself is never refused. A rejoin starts as INTERESTED and needs a new commitment (new deposit); the earlier deposit was already refunded on leave. Interpretation recorded here: "at most 2 leaves" is enforced on the rejoin, not on the leave. Modelled as MEM-T01 on the LEFT row (a new episode), so LEFT stays terminal per episode and `leave_count` is kept.
- Rule (Q-043): passkeys are optional for buyers and dealer staff and required for platform admins: a session opened with an email code never carries `platform_admin` (identity drops it at validation), so admin powers need a passkey sign-in. Attestation conveyance `none`. Email OTP stays the recovery path for buyers and dealer staff; admin recovery is documented in docs/runbooks/admin-recovery.md.
- Rule (Q-044): revoking a staff member ends all their sessions at once: identity deletes them and emits `SessionRevoked` (reason STAFF_REVOKED) per session plus `StaffRevoked`. The gateway and realtime evict on the event; the 25 s session cache remains the fallback guarantee.
- Source: the owner's Phase 2.5 instructions (2026-10-25). Files: OPEN_QUESTIONS.md, membership state machine, transitions vectors, problem-types.yaml, auth-contract.md. Phase: P2.5.

**AMENDMENT-009 — Dealer reliability score (closes Q-036).**
- Rule: reliability = FINAL commitments honoured through to delivery over the last 90 days, `honoured / (honoured + not honoured)`. Honoured = a booking delivered (`BookingDelivered`). Not honoured = a booking cancelled by the dealer, not confirmed within the 48 h window, or cancelled for missing stock (`BookingCancelled` with reason `DEALER_CANCELLED`, `DEALER_NO_CONFIRM` or `STOCK_UNAVAILABLE`), or a quote withdrawn after FINAL (`QuoteWithdrawn{after_final: true}`). Buyer cancellations and open bookings do not count. Each event counts once (by event id).
- Display: always with both counts and the window ("9 of 10 FINAL offers honoured, last 90 days"); never a bare percentage. With no outcomes yet the score is absent ("No completed bookings yet"), not 0 % or 100 %.
- Owner: dealer service (Q-021). Exposed on `GET /dealerships/{id}` as `reliability {honoured, total, score_percent, window_days}`; `ReliabilityScoreChanged` on every counted outcome.
- Source: the owner's P4.E instruction (2026-10-26: "a simple, documented formula ... show it with the sample size"). Files: OPEN_QUESTIONS.md (Q-036), cancellation-rules.md, Reputation.java. Phase: P4.

**AMENDMENT-010 — Notification subscriptions are dedupe-only (refines AMENDMENT-006 for notification).**
- Rule: notification's subscriptions use a third kind, `EVENTS`: inbox dedupe on event_id only, no aggregate_version stale rule. Each event is its own message and never overwrites state, so dropping an older version would lose a message. Queues stay standard (AMENDMENT-006). Recipients are resolved from a graph that tolerates any arrival order (membership links keep their own versions). Only notification may declare `EVENTS` (validate_spec.py).
- Reason: with standard queues and several pollers, a backlog replay applies v11 before v2..v10 and the stale rule drops them (P5.A isolation requirement: backlog delivered exactly once).
- Source: the owner's answer in P5.A (2026-10-26). Files: event-catalogue.yaml (header, subscriptions), validate_spec.py, Subscriptions.java, InboxConsumer.java, gen_contracts.py. Vectors: none. Phase: P5.

**AMENDMENT-011 — Email over SMTP in every environment (replaces the SES API for email).**
- Rule: notification and identity send email over SMTP with the same `spring.mail` settings (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`) through Spring's JavaMailSender. Locally the server is Mailpit; on AWS it is the SES SMTP interface, with credentials from Secrets Manager (P6). The LocalStack SES path is removed. Retries, backoff, the DEAD state and dedupe are unchanged.
- Reason: LocalStack community SES accepts and stores mail but does not relay it to SMTP (observed in P5.A: `SMTP_HOST` set, SendEmail 200, nothing in Mailpit), so the local sink could not be verified.
- Source: the owner's decision (2026-10-26). Files: services.md, notification Mailer + application.yml, identity application.yml, docker-compose.yml, NotificationIT. Vectors: none. Phase: P5 (P6 adds the SES SMTP credentials).

### Contract additions in P5 (owner's P5.A instructions, 2026-10-26)
- notification consumes `MemberLeft` (a buyer who left stops getting pool messages).
- E158 `POST /me/notifications/read-all`, E159 `GET /me/notification-preferences` next to E130–E132; preferences are per user and channel (IN_APP, EMAIL), not per kind; payment and refund messages are always sent.
- notification → identity `contacts:read` (E157) to read the recipient's email at send time.
- Email is sent in English while the Hindi templates are drafts; in-app messages follow the user's locale and mark Hindi as a draft.

### Contract additions in P4 (owner's P4 instructions, 2026-10-26)
- E154 `POST /bookings/{id}/confirm-delivery` (buyer): the owner's P4.D flow has the buyer confirm delivery (BKG-T05 by the buyer), next to E090 where the dealer scans the buyer's voucher. Either releases the deposit and posts the success fee.
- E155 `GET /dealer/bookings/{id}/contact` (dealer staff) and E157 `GET /internal/users/{id}/contact` (identity, `service:order`, scope `contacts:read`): contact release to the dealer of the confirmed quote only. The buyer consents when confirming (`order.contact_consents`, version `2026-10-26-p4`); the release is a saga step after the deposit hold and stock reservation (`order.contact_releases`).
- E156 `GET /admin/disputes` (platform_admin): the disputes queue.
- `PoolReopened` / re-round choice built (E042, POOL-T09/T10, MEM-T12..T16); the choice window is `pool.durations.reround-choice` (48 h; the demo stack uses `DEMO_REROUND_CHOICE_SECONDS`).
- Money outcomes of a dispute (`REFUND_BUYER`, `CANCEL_BOOKING`) follow AMENDMENT-001: the owning admin proposes, a second, different admin confirms the same outcome.
- The voucher is valid only after the saga's BOOKINGS_ISSUED step (deposit held, stock reserved); before that E085 answers `invalid-transition`.
- `PoolClosed` refund reasons follow deposit-contract.md §3 exactly: `POOL_<end_state>`, and `QUOTE_VOID` for `ALL_QUOTES_VOID` (P1 had sent `POOL_<end_reason>`).

### Contract additions in P3 (owner's P3.A instructions, 2026-10-25)
- Dealership status `PENDING` renamed `PENDING_VERIFICATION`; new `REJECTED` (admin rejects with a reason). Every registration and decision is audited (`dealer.dealership_audit`).
- `DealershipVerified` payload: `brand_ids: uuid[]` became `brands: string[]` (dealerships store brand names, as pools do) and gained `registered_by`; identity now consumes it and makes the registrant the dealership's admin.
- E153 `GET /admin/dealerships` (platform_admin): the verification queue. E010/E011 staff invites built (token hashed, one use, bound to the invited email, expiry `identity.invite-hours` = 72).

### Contract additions in P1 (no business-rule change)
Interface additions made while building the walking skeleton. None changes a rule above; each is reflected in the linked spec file and validated by `validate_spec.py`.

| Addition | Why | Files |
|---|---|---|
| Problem type `not-implemented-in-p1` (501) | P1 prompt: commands outside the skeleton return 501 so gaps stay visible. Removed once every command is built. | problem-types.yaml |
| E029 `GET /internal/models/{slug}` (catalog, `service:pool`, scope `models:read`) | pool must learn a model's brand, variants and variant facts without trusting the client. | api-outline.md, auth-contract.md §5 |
| `QuoteRoundOpened` / `FinalRoundOpened` carry `state_code`, `variants[]` | quote prices quotes without a sync call to catalog ("catalog down → submit still works", services.md). | event-catalogue.yaml |
| `QuoteFinalised` carries `ruleset_version`, `tiers[]`, `variant_inputs[]` | order computes CEILING/FINAL snapshots with the engine without a sync call to quote. | event-catalogue.yaml |
| New consumers: quote ← `MemberCommitted`, `MemberLeft`, `AcceptanceChanged`, `ConfirmationRecorded`; order ← `AcceptanceChanged` | quote shows offers only to committed members and needs N for the AMENDMENT-005 projection; order builds the acceptance read model named in the P1 prompt. | event-catalogue.yaml |
| E014 consent and E052 dealership profile built in P1 (outline phases P5/P3) | The P1 prompt asks for dealer-disclosure consent records and a dealer read API. | api-outline.md (phase column unchanged) |
| Pre-commit copy aligned with AMENDMENT-002 | Step 0 missed the VOID line of the copy. | cancellation-rules.md |

### Contract additions in P2 (no business-rule change)

| Addition | Why | Files |
|---|---|---|
| `subscriptions:` section: one kind per (consumer, aggregate), FULL or PARTIAL | P2 step 0.4: every consumer ignores stale versions; only FULL subscriptions (they receive every event type of the aggregate) treat a version gap as real (park, retry, owner snapshot after 5 min). Generated into `SubscriptionKinds`; services cannot pick their own ordering, and a subscription missing from the catalogue fails at startup. | event-catalogue.yaml, validate_spec.py |
| realtime ← `MemberReleased`; `MemberReleased` carries `interested_count`, `committed_count` | realtime must see every pool event to be a FULL subscriber (its clients apply deltas only when `version == local + 1`), and a release changes the counts it shows. | event-catalogue.yaml |
| E043 `GET /me/preferences`; E044/E045 pool internal access and version for realtime (`pools:access`); E030 takes `?model=` | The preferences form needs to read what it saved; realtime must authorise a pool stream and resync after a gap without holding pool data; suggestions are for the buyer's saved models (catalog) without a pool -> catalog call. | api-outline.md, auth-contract.md §5 |
| Heartbeat is a named SSE event; 20 s client watchdog; graceful close on shutdown | `EventSource` cannot read SSE comments; a killed instance leaves silent streams. E121/E122 answer 501 in P2 (they need quote/order/payment events). | realtime-recovery.md §2 |
| Every endpoint answers with a declared record (OpenAPI required/nullable per field) | P2 step 0.2: the web client is generated, never hand-written; CI fails if it drifts. | services/*/Api.java, web/lib/api |

### PLAN.md edits log
_None. PLAN.md is unchanged in P0._
