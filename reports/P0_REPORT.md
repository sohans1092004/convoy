# P0 Report — Rules and Spec (Convoy)

Branch `p0/spec` · 2026-10-24 · PLAN.md v3 is unchanged. No application code was written, no AWS resources were created, and no paid services were used.

## 1. Summary
P0 turned PLAN.md into a frozen, testable specification. The entry point is **[SPEC.md](../SPEC.md)**: product summary, scope and non-goals, frozen decisions, change control and the full document index.

- **State machines as data:** 9 YAML machines with 80 transitions (111 from → to edges). Eight were requested (pool, round, quote, order, payment, refund, booking, dispute); `membership` was added because commit, leave and accept drive counts and deposits. Each transition has trigger, guard, effects, event, repeat behaviour, late-arrival behaviour, crash points and timeout/detector. Each machine lists invalid commands with RFC 9457 problem types and terminal states. Mermaid diagrams and the exhaustive invalid (from → to) pairs are generated into the [state-machine README](../docs/rules/state-machines/README.md).
- **Business rules:**
  - [tier resolution](../docs/rules/tier-resolution.md): 30 vectors;
  - [deposit contract + ledger + reconciliation](../docs/rules/deposit-contract.md): 23 vectors covering every PLAN §2.3 row;
  - [pricing model](../docs/rules/pricing-model.md): 51 vectors, **6 hand-verified**;
  - [savings](../docs/rules/savings-model.md), [ranking](../docs/rules/ranking-rule.md), [deadline](../docs/rules/deadline-rule.md), and [cancellation](../docs/rules/cancellation-rules.md) with the exact pre-commit UI copy.
- **Executable contract:**
  - [price_ref.py](../spec-tools/price_ref.py): stdlib, integer paise, explicit rounding order;
  - [gen_vectors.py](../spec-tools/gen_vectors.py): refuses to write if the engine disagrees with the hand-verified totals;
  - [vectors](../spec-tools/vectors/): pricing, tiers, deposit outcomes, transitions.
- **Architecture:**
  - [services.md](../docs/architecture/services.md): 11 services covering tables, APIs, events, scaling signal, wave and behaviour when dependencies are down;
  - [event catalogue](../docs/architecture/event-catalogue.yaml): 67 events, each with one owner, listed consumers, and FIFO only for pool events; plus 68 generated JSON Schemas;
  - [API outline](../docs/architecture/api-outline.md): 104 endpoints, each with role, idempotency/If-Match, problem types and authz test IDs;
  - [auth](../docs/architecture/auth-contract.md), [realtime recovery](../docs/architecture/realtime-recovery.md), [deployment profiles](../docs/architecture/deployment-profiles.md) with claims tables, and [threat model](../docs/architecture/threat-model.md) (STRIDE per service + marketplace integrity).
- **Decisions and tests:**
  - [ADR-001…012](../docs/adr/README.md), each stating its alternatives honestly;
  - test specs: [authz matrix](../docs/testing/authz-matrix.md) (AZ-T1…T7 + named tests), [messaging failure tests MF-1…6](../docs/testing/messaging-failure-tests.md), [load workloads W1–W9](../docs/testing/load-workloads.md), [frontend quality](../docs/testing/frontend-quality.md).
- **Data:** [catalogue](../docs/data/catalogue/models.yaml) (30 models / 8 brands) with [SOURCES.md](../docs/data/catalogue/SOURCES.md); [TCS](../docs/data/rules/tcs.yaml), [RTO](../docs/data/rules/rto.yaml), [insurance baseline](../docs/data/rules/insurance-baseline.yaml).
- **Cost:** [AWS cost estimate](../docs/cost/aws-cost-estimate.md) using Price List API prices, with [reproducible arithmetic](../docs/cost/cost-arithmetic.md).
- **Product:** [personas & journeys](../docs/product/personas-and-journeys.md), [metrics](../docs/product/metrics.md), [glossary](../docs/GLOSSARY.md).
- **Wireframes:** [index](../design/wireframes/index.html), covering 7 screens × 2 widths × 8 states. They are static and grayscale, use one shared CSS file, switch states with CSS only, are keyboard operable, and were render-checked in headless Edge.
- **Validator:** [validate_spec.py](../spec-tools/validate_spec.py), run by the [CI workflow](../.github/workflows/spec.yml) on every push, together with a PyYAML parity check for the stdlib YAML loader.

## 2. Decisions made by default
All 40 are in [OPEN_QUESTIONS.md](../docs/OPEN_QUESTIONS.md) with options and impact. None contradicts PLAN.md.

| Q | Chosen default |
|---|---|
| Q-001 | TCS threshold compared on vehicle consideration = GST-inclusive ex-showroom − dealer discount; other lines excluded |
| Q-002 | TCS rounded half up to the rupee |
| Q-003 | A manufacturer price with no city is applied to each plan city as an ASSUMPTION ("city not confirmed") |
| Q-004 | Acceptance owned by pool, through an added `membership` machine; order starts at CONFIRMATION_PENDING |
| Q-005 | Join/commit allowed until the sealed-round deadline |
| Q-006 | Deadlines immutable; enforcing services hold immutable replicas |
| Q-007 | Single re-round returns the pool to FORMING; members re-commit (their deposits were already refunded) |
| Q-008 | Dropping below the minimum after READY does not revert the pool |
| Q-009 | Zero bookings and no re-round → EXPIRED (`ALL_QUOTES_VOID` / `NO_CONFIRMATIONS`) |
| Q-010 | Payment states VOIDED_UNPAID, REFUND_PENDING and terminal SETTLED added |
| Q-011 | Tier ladders contiguous, last tier unbounded; gaps rejected |
| Q-012 | Optional `max_units`; confirmations first come by commit order |
| Q-013 | Tiers expressed as a discount; price is always engine output |
| Q-014 | Discount base EX_SHOWROOM only; 25 % cap |
| Q-015 | Road-tax base = price after discount; inclusive upper slab bounds |
| Q-016 | Insurance estimate: IDV 95 %, OD 2 % (assumption), TP by cc, 18 % GST; no estimate for EVs |
| Q-017 | Optional items count only if selected; never in ranking |
| Q-018 | Each percentage line rounded independently, in a fixed order |
| Q-019 | Overdue booking = flag via self-transition; 7-day grace |
| Q-020 | Disputes allowed up to 90 days after delivery |
| Q-021 | Reviews → dealer, clarifications → quote, disputes/bookings → order, analytics → audit |
| Q-022 | Durations 7 d / 72 h / 24 h / 48 h / 48 h; dealer confirmation 48 h |
| Q-023 | Dealer cancellation after FINAL (before booking) auto-opens disputes |
| Q-024 | Ranking tie-break uses the commit time of the ranked version |
| Q-025 | Purchase window ends on the last day of the intended month (IST) |
| Q-026 | Refunds always full |
| Q-027 | Realised saving needs a buyer-confirmed invoice |
| Q-028 | Registration estimate = road tax (+ KA cess) + ₹600 + ₹200; other fees itemised by the dealer |
| Q-029 | Unverified state/fuel rules → engine refuses; conflicting → estimate labelled UNVERIFIED |
| Q-030 | FASTag/fees entered by the dealer only |
| Q-031 | Ranking uses the dealer's figure for standard insurance cover if given |
| Q-032 | Tier count per quote, not per variant |
| Q-033 | Gateway fees not modelled in the test-mode ledger |
| Q-034 | Ranking at the ceiling tier |
| Q-035 | Dealers see `of_n` |
| Q-036 | Reliability formula deferred to P3 |
| Q-037 | Route JS budget caps provisional until the P1 baseline |
| Q-038 | Assume the AWS paid plan for full runs |
| Q-039 | One acceptance per buyer, changeable until the decision deadline |
| Q-040 | Second-admin threshold ₹5,000 |

## 3. Verified data

### Catalogue
Only official manufacturer pages were used. CarDekho was rejected because its terms forbid automated copying; one evaluation read was discarded.

| Measure | Count |
|---|---|
| Variant prices verified (named variant, official page) | **31** |
| Variant prices unverified | **10** |
| Model starting prices verified (Venue, Verna, Exter, Sonet) | 4 |
| Model prices seen but not labelled ex-showroom (kept UNVERIFIED) | 7 |
| Models with **no verified price** | **17 of 30** |
| City-verified variant price rows (Brezza, "Ex-showroom Price (Delhi)") | 3 of 124 |
| Variant × city rows priced by ASSUMPTION (city not stated; Q-003) | 121 of 124 |

The 17 models without verified prices: Tata ×5 (the site had an expired TLS certificate, which I did not bypass), Toyota ×3 (HTTP 403/404), Baleno and Fronx (no prices rendered), Mahindra ×4 (unlabelled ranges only), and Carens, City and Elevate (prices not labelled ex-showroom). Sources used: Maruti Suzuki Arena, Hyundai India, Kia India, MG Motor India, Mahindra, Honda Cars India.

### TCS rule as verified
| Element | Verified value |
|---|---|
| Provision | Income-tax Act, 2025, **s.394(1) Table Sl. No. 6** (formerly s.206C(1F) of the 1961 Act) |
| Text | *"Sale consideration exceeding ten lakh rupees in case of — motor vehicle"* · collected by the seller · rate **1 %** |
| In force | **1 April 2026** (Act No. 30 of 2025 commencement; CBDT press release) |
| Threshold condition | strictly **exceeding** ₹10,00,000 (₹10,00,000.00 → no TCS) |
| Base | 1 % of the **whole** consideration, not the excess |

Caveats:
- The official incometaxindia.gov.in page returned 403 to my fetch, so the statutory text was read from a third-party copy (eztax.in), corroborated by TDSMAN.
- No post-GST CBDT clarification on whether GST is included in the threshold amount was found (Q-001).
- The non-PAN rate is UNVERIFIED.

### RTO coverage
All sources are secondary (third-party summaries; no official gazette text was read), and every figure is labelled as an estimate.

| State | Coverage |
|---|---|
| **Maharashtra** (Mumbai, Pune) | Petrol 11/12/13 %, diesel 13/14/15 %, EV 0 %, ₹30 lakh tax cap; effective 2025-07-01. CNG 8/9/10 % (sources conflict, so labelled UNVERIFIED). Hybrid UNVERIFIED. |
| **Delhi** | Petrol 4/7/10 %, diesel 5/8.75/12.5 % (bands ≤ ₹6 L / ≤ ₹10 L / above). CNG, EV and hybrid UNVERIFIED. |
| **Karnataka** (Bengaluru) | ICE cars 13/14/17/18 % + 11 % cess on tax + ₹1,000 cess. EV 5/8/10 % from 2026-04-01. |
| **Common fees** | ₹600 registration (secondary); ₹200 smart card (sources conflict on when it applies). |

Insurance: 3-year third-party premiums of ₹6,521 / ₹10,640 / ₹24,596 come from a secondary source. The OD rate and GST are labelled ASSUMPTION, and the EV third-party premium is UNVERIFIED.

## 4. Cost estimate (ap-south-1, Price List API, 2026-10-24)
| Item | Estimate |
|---|---|
| **Lite, per month** | **≈ $37.6** on t4g.medium (4 GB) · **≈ $53.9** on t4g.large (8 GB). Free tiers not applied, so these are upper bounds. |
| **Full, per hour** | **≈ $1.49** idle · **≈ $4.61** under load · plus **≈ $8.44** while 1,000 req/s go through CloudFront + WAF |
| **Full, per representative 6-hour run** | **≈ $31.4** (1.5 h idle + 4.5 h load + 1 h edge segment) |
| Full left on for a month | ≈ $1,086, which is why it is never always-on |

Biggest cost drivers:
1. CloudFront and WAF request charges under synthetic load (≈ $6.5/h). Mitigation: load the ALB origin directly and send only the W9 segment through the edge.
2. RDS Multi-AZ, $0.479/h.
3. X-Ray traces and log ingest.
4. Fargate.
5. SQS requests.

Credits: the $200 covers about 4 months of lite plus about 1 full run, or about 6 full runs on their own. They do not cover always-on full, and they expire. Assume the paid plan is needed (Q-038).

Cheapest topologies: lite for the demo; for latency, SSE and saga claims, full with single-AZ RDS, one NAT and 1 % tracing (≈ $3.6/h); Multi-AZ only for the failover drill.

## 5. Spec risks
1. **Tier resolution owner (underspecified).** PLAN §2.1 puts CONFIRMATION → BOOKING on the pool, with tier resolution as a "saga step". The data the tier needs (confirmations) and the saga belong to order. Resolution now lives in order (one transaction per pool), and pool moves on `BookingsIssued`.
2. **Re-round path missing (underspecified).** PLAN's pool machine has no path for the single re-round after VOID, and VOID refunds deposits automatically. I added CONFIRMATION → FORMING with re-commitment (Q-007). That adds friction, so the product should validate it in the beta.
3. **Payment states (contradictory as written).** "Each deposit has exactly one terminal outcome (applied or refunded)" conflicts with "refund if the dealer or buyer cancels after booking", because APPLIED_TO_BOOKING can still be refunded. Fixed by making SETTLED (delivered) the terminal "applied" outcome (Q-010).
4. **"Confirmed = confirmation + deposit captured" is redundant.** The deposit is captured at commit, weeks earlier. The check is kept but can never bind; the real risk is a delayed webhook at commit time, not at confirmation.
5. **Lite at 4 GB looks infeasible.** 11 Spring Boot JVMs plus PostgreSQL and Redis in 4 GB needs ~150 MB heaps per service. Expect 8 GB. This must be measured in P6 as PLAN says.
6. **SNS FIFO delivery to consumers (unverified).** Pool events on an SNS FIFO topic. Whether non-ordering consumers such as notification and audit can subscribe with standard SQS queues, or must use FIFO queues with lower throughput, was not checked in P0. Verify before P2.
7. **Data verifiability is weaker than PLAN implies.**
   - 17/30 models have no verified price;
   - city-specific ex-showroom prices are almost never published (121/124 rows are assumptions);
   - all RTO rules are secondary-sourced;
   - the OD insurance rate is a placeholder.

   Mitigations are already in place: the evidence labels make all of this visible, and P1 only needs the verified subset.
8. **TCS base ambiguity.** Whether GST counts towards the threshold is unclarified. This matters only near ₹10 lakh, but the UI copy and vectors depend on Q-001.
9. **Edge costs under load.** PLAN's cost table did not anticipate that CloudFront + WAF per-request pricing dominates a 1,000 req/s test. Load design is adjusted accordingly.
10. **Ambiguous "round deadline" for dealer withdrawal (§2.6).** Interpreted as the current round's deadline. After the final deadline, withdrawal counts as a dealer cancellation.
11. **Scope pressure.** 11 services × 104 endpoints is very large for one person; ADR-001 records that a modular monolith would also serve the target. The wave plan is the main mitigation.

## 6. Validator output
```
$ python spec-tools/validate_spec.py
PASS services.md defines the 11 services
PASS state machines: states, fields, crash points, reachability, terminals
PASS state machines README is generated and current
PASS event catalogue: one owner, real consumers, audit consumes all, unique names
PASS every event emitted by a state machine exists, owned by the machine owner
PASS event JSON Schemas are generated and current
PASS api-outline: every endpoint maps to a service, known roles, known problem types, authz test IDs
PASS vectors: parse, minimum counts, regenerate identically
PASS price_ref reproduces every pricing and tier vector exactly (incl. snapshot hash)
PASS data values carry a source or an UNVERIFIED/ASSUMPTION label
PASS wireframes: 7 screens x 8 states x 2 widths, linked from index, shared CSS
PASS every Q-### referenced exists in OPEN_QUESTIONS.md
PASS relative links in markdown files resolve

13/13 checks passed
```

- The validator caught one real defect during P0: MEM-T06 left terminal states. It was fixed by routing late captures for departed members through payment (PAY-T10).
- A mutation check confirmed that it fails on a tampered vector and on a wrong event owner.
- The CI result for the pushed branch is linked in the PR.

## 7. What P1 can start with
P1 can start immediately with:
- **Wave-1 services, thin:** gateway, identity, catalog, pool, dealer, quote, order, payment. Owned tables are in services.md, and P1 endpoints are marked `P1` in api-outline.md.
- **State machine YAML as test tables:** `spec-tools/vectors/transitions.json`, including invalid pairs and commands.
- **The price engine contract:** Java and TypeScript engines must pass `pricing.json` (51 cases) and `tiers.json` (30 cases) byte for byte (ADR-011).
- **Event envelope + JSON Schemas** for consumer-driven contract tests; the outbox/inbox library is tested against MF-1…MF-6 later in P2.
- **Auth contract** (BFF session → gateway JWT) and the authz test IDs attached to each P1 endpoint.
- **Catalogue seed** from verified variants with known fuel: Swift, Dzire, Brezza, Ertiga, Creta, Windsor EV, Hector. The RTO/TCS/insurance ruleset `2026.10.24-p0` covers MH/DL/KA.
- **Wireframes** for the 5 flagship screens and the quote builder, as the layout and state reference.

Blockers and prerequisites (none blocks starting):
- **Razorpay test-mode account and keys**, plus a webhook secret and tunnel for local webhooks. This is your action; no secrets are in the repo.
- **Your review of the 40 defaults** in OPEN_QUESTIONS.md. The ones most worth a look are Q-003, Q-007, Q-009, Q-010, Q-014 and Q-031. Changing one after merge needs an amendment.
- **Re-verifying Tata/Toyota prices** (not needed for P1), and the SNS FIFO → standard-queue question (risk 6) before P2.

P1 was not started.
