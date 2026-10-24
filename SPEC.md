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
   - the 40 defaults in [OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md).

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
_None yet._

### PLAN.md edits log
_None. PLAN.md is unchanged in P0._
