# Open questions and defaults chosen in P0

Each item is a rule PLAN.md leaves ambiguous, or a value that could not be verified. P0 chose the option most consistent with PLAN.md and recorded it here. **None of these choices contradicts PLAN.md.** After P0, changing one of them needs an ADR or an `AMENDMENT-###` entry in SPEC.md.

Format: question · options · **default chosen** · impact.

### Q-001 — Which amount is compared with the ₹10 lakh TCS threshold?
- Options: (a) ex-showroom before discount; (b) vehicle sale consideration = ex-showroom (GST-inclusive) − dealer discount; (c) the whole invoice, including accessories and fees.
- **Default: (b).** Registration, insurance, accessories, warranty and fees are excluded.
- Impact: a discount can move a car below the threshold (vectors P003–P008). No post-GST CBDT clarification on GST inclusion was found, so this needs review before any real launch.

### Q-002 — How is TCS rounded?
- Options: paise; nearest rupee half up; nearest ₹10.
- **Default: nearest rupee, half up.**
- Impact: at most ₹0.50 difference per order (vectors P007, P008).

### Q-003 — Which city does a manufacturer price apply to when the page names no city?
- Options: (a) treat it as UNVERIFIED for every city; (b) apply it to each plan city as an ASSUMPTION, labelled "city not confirmed".
- **Default: (b).** The evidence level is CATALOGUE_ESTIMATE, and the UI text says the city is not confirmed. Only city-labelled prices (Brezza for Delhi, Sonet for all India) count as city-verified.
- Impact: 121 of 124 variant × city rows rest on this assumption (catalogue SOURCES.md).

### Q-004 — Who owns acceptance, and does membership need its own machine?
- Options: acceptance in order (orders created at accept) vs in pool (a membership field).
- **Default:** acceptance lives in **pool**, through a ninth machine, `membership.yaml`. pool already owns the decision deadline, and the members with no acceptance can be released atomically at AcceptancesFrozen.
- Impact: order rows start at CONFIRMATION_PENDING. Deposit refunds for non-accepters are driven by `AcceptancesFrozen.released_buyers`.

### Q-005 — Until when can buyers join or commit?
- Options: FORMING only; until the sealed-round deadline; until the decision deadline.
- **Default: until the sealed-round deadline** (states FORMING, READY_FOR_QUOTES, QUOTE_OPEN).
- Impact: dealers see a live count during the sealed round. The final round has a fixed membership.

### Q-006 — Can deadlines be extended, and who enforces a deadline owned by another service?
- **Default:** deadlines are immutable once set. An enforcing service (quote for rounds, order for confirmation) keeps an immutable replica, copied from the opening event.
- Impact: no extension feature. An admin can only cancel the pool.

### Q-007 — What does the single re-round after a VOID look like?
> **Superseded by AMENDMENT-002 (P1).** VOID deposits are not refunded when the pool re-rounds; each affected buyer chooses keep or refund within 48 h, and no response means refund.
- Options: (a) reopen quoting with the same commitments; (b) return to FORMING and have members commit again.
- **Default: (b).** PLAN §2.2.6 requires automatic refunds on VOID, so the original commitments no longer hold a deposit.
- Impact: POOL-T09. `round_set = 2`. A re-round is allowed only if a full cycle fits before `purchase_window_end`.

### Q-008 — Does leaving below the minimum after READY_FOR_QUOTES move the pool back to FORMING?
- **Default: no.** The minimum only gates the start of quoting. Dealers are protected by `min_fulfilment`.
- Impact: simpler machine, and dealers may quote for a shrinking pool. Counts stay visible to everyone.

### Q-009 — What terminal state does a pool get with zero bookings and no re-round?
- Options: NO_QUOTES vs EXPIRED.
- **Default: EXPIRED**, with reason `ALL_QUOTES_VOID` or `NO_CONFIRMATIONS` (insufficient demand). NO_QUOTES is kept for "no valid dealer offer".
- Impact: refund reason `POOL_EXPIRED` for buyers whose deposit was not already refunded.

### Q-010 — Which payment states are added to PLAN's list?
> **Clarified by AMENDMENT-003 (P1).** `APPLIED_TO_BOOKING` is renamed `HELD_FOR_BOOKING`; `SETTLED` is replaced by `RELEASED` (deposit returned after delivery). The deposit is never paid to the dealer.
- **Default:** add `VOIDED_UNPAID` (abandoned or cancelled before capture), `REFUND_PENDING` and the terminal `SETTLED` (applied to a delivered booking). APPLIED_TO_BOOKING stays non-terminal, because a booking can still be cancelled and refunded.
- Impact: this keeps the invariant "exactly one terminal outcome (applied or refunded)" true.

### Q-011 — Which tier ladders are valid?
- **Default:** contiguous inclusive bounds; the last tier is unbounded; `min_fulfilment ≥ first tier min`; price non-increasing. **Gaps are rejected** at submit, not interpreted.
- Impact: vectors T16–T21.

### Q-012 — What happens when confirmations exceed the dealer's stock?
- **Default:** optional `max_units` per quote. Confirmations beyond it get `409 capacity-reached`, first come by DB commit order.
- Impact: the tier count never exceeds `max_units` (T27, T28).

### Q-013 — How is a tier expressed?
- Options: an absolute price per tier, or a discount per tier.
- **Default:** a discount on ex-showroom per tier (per covered variant). The buyer-facing price is always the engine output.
- Impact: road tax and TCS follow the discount automatically, so the price stays monotonic.

### Q-014 — Which discount bases are allowed, and is there a cap?
- **Default:** only `EX_SHOWROOM` in P0, capped at 25 % of ex-showroom as a data-entry guard. Accessory discounts are expressed as lower accessory prices.
- Impact: vectors P037–P040. The cap value is an assumption.

### Q-015 — What base does the road-tax estimate use?
- **Default:** vehicle price after discount (an invoice-like value), with inclusive upper slab bounds.
- Impact: Karnataka at exactly ₹10 lakh → 14 % (P022). This conflicts with one source's table and is recorded in rto.yaml.

### Q-016 — How is the insurance baseline estimated?
- **Default:** IDV = 95 % of ex-showroom; OD = 2.00 % of IDV (placeholder ASSUMPTION); 3-year TP by cc band (secondary source); 18 % GST (ASSUMPTION). No estimate for EVs: the dealer must enter it.
- Impact: estimates are labelled. Ranking uses dealer figures when present (Q-031).

### Q-017 — Do optional items count in cash payable?
- **Default:** only when the buyer selects them, in BUYER_CONFIG mode. Never in RANKING mode.
- Impact: vectors P027–P031.

### Q-018 — Where does rounding happen?
- **Default:** each percentage-derived line is rounded half up to the rupee, independently, in the documented order. Totals are never re-rounded.
- Impact: Java and TypeScript must follow the same order (vectors P042, P043, P049).

### Q-019 — How is an overdue booking represented?
- **Default:** a flag set by a daily job through self-transitions BKG-T07/T08, with a 7-day grace after the promised date. The state is unchanged.
- Impact: the buyer is prompted to open a dispute (PLAN §2.6).

### Q-020 — How long after delivery can a buyer open a dispute?
- **Default: 90 days.**
- Impact: DSP-T01 guard.

### Q-021 — Which service owns the features PLAN doesn't place?
- **Default:** reviews and reliability → dealer; clarifications → quote; bookings, disputes and invoice confirmation → order; admin read models and analytics views → audit.
- Impact: services.md.

### Q-022 — What are the default durations?
- **Default:** forming 7 days; sealed round 72 h; final round 24 h; decision 48 h; confirmation 48 h; dealer booking confirmation 48 h; dispute info response 72 h; appeal window 7 days. All configurable per environment.
- Impact: wireframe dates and load-test datasets.

### Q-023 — Does a dealer cancelling after FINAL, before any booking exists, open a dispute?
- **Default: yes.** A dispute is auto-opened for each affected buyer (ORD-T10), as with the post-booking case.
- Impact: admin workload, and a consistent dealer-accountability signal.

### Q-024 — What does "earliest submission" mean in the ranking tie-break?
- **Default:** the commit time of the quote version being ranked, not the first submission.
- Impact: a late revision loses tie-breaks; this rewards a firm early best offer.

### Q-025 — How is the purchase window defined?
- **Default:** `purchase_window_end` = the last day (23:59:59 IST) of the pool's intended purchase month, set at creation.
- Impact: limits the re-round (Q-007).

### Q-026 — Can refunds be partial, or carry fees?
> **Clarified by AMENDMENT-003 (P1).** Every deposit ends REFUNDED or RELEASED, both full. The success fee is a separate dealer receivable.
- **Default:** all refunds are full deposit refunds. No fees are retained in test mode.
- Impact: refund amount = payment amount.

### Q-027 — What proves a realised saving?
- **Default:** an invoice total entered or uploaded by the buyer and confirmed by them. With no invoice, no realised-saving claim.
- Impact: savings label 4.

### Q-028 — What does the registration estimate include?
- **Default:** road tax (+ KA cess) + ₹600 registration + ₹200 smart card. HSRP, parking (Delhi MCD), hypothecation and fancy numbers are excluded and must be itemised by the dealer.
- Impact: estimates understate those fees, and the UI says so.

### Q-029 — What happens where state rules for a fuel are unverified?
- **Default:** the engine refuses to estimate (`REGISTRATION_ESTIMATE_UNAVAILABLE`), so the dealer must enter a figure. Conflicting rules (MH CNG) are estimated but labelled UNVERIFIED.
- Impact: hybrids in MH/DL and CNG/EV in DL need dealer figures.

### Q-030 — Do FASTag and other fees have catalogue defaults?
- **Default: no.** They are dealer-entered only, with a basis text.
- Impact: no unverified default amounts.

### Q-031 — Which insurance figure does ranking use?
> **Tightened by AMENDMENT-004 (P1).** The dealer figure is used only if it declares exactly the baseline cover and is marked binding; otherwise the baseline estimate.
- **Default:** the dealer's own figure for the standard baseline cover when supplied, otherwise the catalogue estimate.
- Impact: a buyer actually pays the dealer's figure, so ranking reflects it.

### Q-032 — Is the tier count per quote or per variant?
- **Default: per quote** (all covered variants count together).
- Impact: simpler for buyers. Per-variant tiers can be added after P0 by amendment.

### Q-033 — Does the ledger model gateway fees?
- **Default: not in test mode.** A live system would add a fees expense account.
- Impact: ledger simplicity, documented.

### Q-034 — Which tier does ranking use?
> **Extended by AMENDMENT-005 (P1).** Buyers also see a labelled, non-guaranteed "if confirmed count stays at N" projection beside the ceiling.
- **Default:** the dealer's ceiling tier (the tier containing `min_fulfilment`).
- Impact: dealers compete on the guaranteed maximum.

### Q-035 — Do dealers see the number of competitors?
- **Default: yes**, `of_n` per variant, but no values.
- Impact: small information leak, accepted as context (ADR-008).

### Q-036 — What is the dealer reliability score formula?
- **Default:** deferred to P3. P0 defines only the triggering events.
- Impact: none for P1.

### Q-037 — What are the starting route JS budget caps?
- **Default:** caps in frontend-quality.md §3, revised once P1 sets the measured baseline.
- Impact: CI gate values.
- **P2 (2026-10-25):** baseline measured (gzip first-load JS, `web/perf/budgets.json`): discover 185.5 KB, join 189.4,
  pool 189.3, compare 185.3, confirm 194.1, my cars 184.5, quote builder 200.9. Per the P2 prompt the CI budget is
  baseline × 1.10. Five routes exceed the starting caps (discover 170, join 150, pool 170, confirm 150, my cars 170): about
  180 KB of every route is the shared runtime (React 19, Next.js, next-intl, TanStack Query, Radix). Still open: whether to
  cut that (e.g. drop a dependency, split providers) to meet the caps, or raise the caps.

### Q-038 — Which AWS account plan do full runs need?
- **Default:** assume the paid plan. Whether the free plan restricts Fargate, WAF or Multi-AZ RDS was not verified.
- Impact: credits still apply on the paid plan, but a budget action is mandatory.

### Q-039 — How many quotes can a buyer accept?
- **Default:** one quote + variant per buyer at a time, changeable until the decision deadline (MEM-T08).
- Impact: one order per buyer per pool.

### Q-040 — What is the second-admin approval threshold?
> **Superseded by AMENDMENT-001 (P1).** Every manual refund, ledger adjustment or override needs a second admin, whatever the amount; automatic rule-driven refunds do not.
- **Default: ₹5,000**, configurable. Deposit refunds (₹999) are below it; money outcomes of disputes above it need a second admin.
- Impact: AZ-INT-08.

### Q-041 — How much is the platform success fee, and when is it charged? — RESOLVED (AMENDMENT-007)
- **Resolved 2026-10-24:** flat ₹2,500 (250000 paise) per delivered booking, a dealer receivable (J-FEE in PAY-T09), configurable, never from a buyer deposit.
- Added in P1 by AMENDMENT-003, which makes the fee a separate receivable from the dealer. PLAN.md does not state an amount.
- **Default:** `success_fee_paise` is unset, so no J-FEE entry is posted. When set, J-FEE (Dr 1300 / Cr 4100) is posted when a booking is delivered (PAY-T09).
- Impact: none for the P1 journey, which ends at the booking voucher. Needs a business decision before any dealer agreement.

### Q-042 — What is the default pool minimum (`min_members`)? — RESOLVED (AMENDMENT-007)
- **Resolved 2026-10-24:** 5 in production, 1 in the local demo stack.
- Added in P1. PLAN.md requires a minimum before quoting (POOL-T01) but states no number.
- **Default:** a required setting, `pool.min-members`. The demo profile uses 1 so a single test buyer can run the journey. No production value is set; it is a business decision.
- Impact: forming deadline outcomes (POOL-T11) and dealer interest; set before any real pilot.
