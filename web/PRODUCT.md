# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Design for buyers; expect reviewers.** Convoy is designed as a real product for ordinary car buyers in India. Today's actual visitor is usually a hiring reviewer trying the public demo, who must grasp the product quickly and experience it as a buyer would.

- **Buyer:** someone buying a new car within a couple of months, mostly on a phone. They want a fair price without haggling and clarity on what "on-road" includes. They worry about hidden charges, pressure, losing the deposit, delivery delays and stock vanishing after booking.
- **Dealer staff:** handle quotes for several pools a week. They want volume without giving away margin, and to know where they stand in the final round.
- **Dealer admin:** owns the dealership profile, staff access and the dealership's own analytics.
- **Platform admin:** Convoy operations. Resolves disputes, keeps refunds flowing and catches abuse, and is wary of mistakes involving money.

The named personas in `docs/product/personas-and-journeys.md` are illustrative, not research findings.

## Product Purpose

Buying a new car in India is a one-on-one negotiation that the buyer usually loses. Convoy lets buyers who want the same model in the same city form a pool, so verified dealers compete for the whole group. Each buyer still decides alone.

The promise is less uncertainty and less effort for ordinary buyers. Success means a buyer can reach a confident decision without haggling, understand every line of the price, and always know what happens to their deposit.

## Positioning

The lead claim depends on where the buyer is:

- **Home page, and anywhere introducing Convoy: dealers compete for you.** Verified dealers bid for the whole pool in sealed rounds, so the buyer doesn't haggle.
- **Compare-offers and confirm screens: prices you can check.** Itemised lines, evidence labels and named comparisons lead, because this is the decision moment.

Supporting mechanisms, which hold everywhere: a buyer confirms at an exact ceiling price that can only fall as more buyers confirm, and acceptance and confirmation stay individual.

## Operating Context

The core journey is discover, join and commit, sealed quote round, best-and-final round, compare, accept (non-binding), confirm, resolution, track, and handover by QR voucher, with disputes and a dealer review along the way.

- Rounds and confirmations run against immutable server-owned deadlines, so counts, countdowns and late or conflicting actions are ordinary states of the product.
- The buyer deals in Indian rupees with lakh and crore grouping, and sees times in IST.
- Evaluators enter through "Try the demo", a 24-hour personal sandbox with a buyer account, a fresh pool, simulated dealers and a demo dealership, plus a read-only admin view with synthetic data.

## Capabilities and Constraints

**Capabilities:** email OTP and passkey sign-in; catalogue and price calculator; saved cars and pool matching; pools and commitment; versioned quotes with stock; structured clarifications; compare, accept and confirm; order tracking with QR voucher; disputes and reviews; in-app, email and web-push notifications; dealer inbox, quote builder, bookings and analytics; admin console; data export and erasure.

**Non-goals:** AI recommendations or chat, native apps, live open auctions, loans and insurance sales, used cars and servicing.

**Terminology** is defined in `docs/GLOSSARY.md` and must be used precisely, especially pool, interested versus committed, round, tier, ceiling price, acceptance versus confirmation, cash payable, estimated net cost, deposit and evidence level (`DEALER_CONFIRMED`, `CATALOGUE_ESTIMATE`, `UNVERIFIED`).

**Rules are frozen.** Business rules live in `docs/rules/`. Changing them requires an ADR or an amendment in `SPEC.md`; a design change cannot quietly alter them. Deposit, refund and cancellation wording follows `docs/rules/cancellation-rules.md` and `docs/rules/deposit-contract.md`.

**Binding constraints:**

- Honesty labels stay visible. "TEST: no real money", "simulated" and synthetic-dealer labels are never softened or hidden, and nothing implies real customers or real savings.
- Every saving claim names what it is compared against. Test deposits are never presented as proof of willingness to buy.
- Buyer screens get the most care. The quote builder and admin console remain dense work tools for frequent users.

**Honest limits:** no real dealers; test-mode payments only; about 30 models with dated, sourced prices; RTO and insurance are estimates; legal review is flagged, not resolved.

**Undecided:** the outcome of the planned usability beta (20 to 50 people), which has not produced results.

## Brand Commitments

The name is Convoy, a working name in `PLAN.md`. The voice is plain, direct, and calm about money: no hype, no urgency tricks, no exaggerated savings language. Deadlines are stated as facts, not pressure. It states exactly what happens to the buyer's money and never oversells; `web/messages/en.json` is the reference.

## Evidence on Hand

- **Catalogue:** ex-showroom prices from official manufacturer pages, each with source and date (`docs/data/catalogue/`). Most apply to a city by a labelled assumption ("city not confirmed").
- **Executable rules:** pricing and tier vectors in `spec-tools/vectors/`, and state machines in `docs/rules/state-machines/`.
- **Quality and deployment records:** phase reports in `reports/`.
- **Wireframes:** `design/wireframes/`.
- **Outside precedent only:** WION reports that JITO members bought about 186 luxury cars through group negotiation in 2025, saving about ₹21 crore. This is not Convoy's result.

**Absent, and must not be fabricated:** real customers, testimonials, real dealers, partner logos, realised savings, press, pricing or fee terms, and beta findings. Marketplace metrics from synthetic data are labelled "simulated".

## Product Principles

1. **Certainty over excitement.** At every step the buyer can see the most they can pay, what each line is based on, and what happens to their deposit.
2. **Introduce with competition, decide with evidence.** Leverage gets the buyer in the door; checkable facts carry the decision.
3. **The group gives leverage, never pressure.** Each buyer decides alone, can leave on the stated terms, and is never rushed by persuasion the rules don't require.
4. **Say what is simulated.** A candid demo is worth more than an impressive one, so the honest limits stay in plain sight.
5. **A real buyer's product, legible to a first-time visitor.** A reviewer should understand Convoy by using it, with no separate story needed.

## Accessibility & Inclusion

- WCAG 2.2 AA, and must work at 360px on a mid-range phone over 4G. Targets and the manual checklist are in `docs/testing/frontend-quality.md`.
- Hindi is a full locale: layouts and copy must hold in both Hindi and English. The current Hindi text is an unreviewed machine draft, and its legal and money wording needs native-speaker review before any real buyer sees it.
- Rupee amounts must read correctly to screen readers, and lakh and crore formatting must be correct in both locales.
