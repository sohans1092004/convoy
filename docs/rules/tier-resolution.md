# Tier resolution — "confirm at the ceiling; the price can only fall" (PLAN §2.2, ADR-007)

Reference: `validate_tiers`, `count_confirmed`, `resolve_tiers` in [`spec-tools/price_ref.py`](../../spec-tools/price_ref.py). Contract: [`spec-tools/vectors/tiers.json`](../../spec-tools/vectors/tiers.json) (30 cases). Owner at runtime: **order** service (resolution transaction ORD-T05/T06/T07); quote validation at submit: **quote** service.

## 1. Data each quote carries (per covered variant)
| Field | Type | Rule |
|---|---|---|
| `tiers[]` | `{min: int ≥ 1, max: int|null, discount_paise: int}` | inclusive bounds; sorted by `min`; contiguous (`tiers[i].min = tiers[i-1].max + 1`); last tier `max = null`; discount non-decreasing with tier (so price non-increasing) |
| `min_fulfilment` | int ≥ 1 | the dealer commits to honour the quote at ≥ this many confirmed buyers; must satisfy `tiers[0].min ≤ min_fulfilment` |
| `max_units` | int ≥ `min_fulfilment` or null | stock-limited capacity; confirmations beyond it are refused (`409 capacity-reached`, first come by commit order, Q-012) |

The **price of a tier** for a buyer is the price engine's output (both headline figures) for the buyer's configuration with `discount = tier.discount_paise`. Because a larger discount lowers the vehicle price, which can only lower road tax (lower or equal slab) and TCS, while insurance depends on ex-showroom only, the cash payable is non-increasing across tiers. `tiers.json` therefore carries `price_paise` per tier directly (the engine output for a fixed configuration) so the selection logic is tested independently of pricing.

### Validation (at submit / revise; error → `422 invalid-tiers`, codes from `validate_tiers`)
`TIERS_EMPTY`, `TIER_BOUNDS_INVALID:i`, `TIER_GAP:i`, `TIER_OVERLAP:i`, `TIER_AFTER_UNBOUNDED`, `LAST_TIER_MUST_BE_UNBOUNDED`, `TIER_PRICE_INCREASES:i`, `MIN_FULFILMENT_INVALID`, `MIN_FULFILMENT_BELOW_FIRST_TIER`, `MAX_UNITS_BELOW_MIN_FULFILMENT`. Gaps are rejected rather than interpreted (Q-011) — vector T16.

## 2. Ceiling price
`ceiling_tier = the tier containing min_fulfilment`; ceiling price = that tier's price. Rationale: below `min_fulfilment` the quote is VOID, so the highest price a confirmed buyer can ever pay is the price at the minimum. Example: tiers 1–4 ₹9,00,000 · 5–9 ₹8,55,000 · 10+ ₹8,40,000 with `min_fulfilment = 5` → ceiling ₹8,55,000 (vector T08). Each accepting buyer receives the ceiling snapshot (engine output + hash) when their order is created (ORD-T01) and must echo the hash when confirming (ORD-T02), so a buyer can only confirm the exact price they saw.

## 3. What counts as a confirmed buyer
A buyer counts towards the tier of their accepted quote **iff, by the database clock of the order service:**
- `confirmed_at < confirmation_deadline` (commit time of ORD-T02), and
- their deposit was captured before the deadline (always true by construction — capture precedes commitment — but checked), and
- they had not cancelled before the deadline (`cancelled_at IS NULL OR cancelled_at ≥ confirmation_deadline`).

Not acceptances (DECISION_OPEN), not completed bookings. A confirmation committing exactly at the deadline is not counted (T25).

## 4. Resolution (one transaction per pool, at `confirmation_deadline`)
For each FINAL quote with ≥ 1 acceptance, per variant group:
1. `n = count_confirmed(...)`.
2. If `n < min_fulfilment` → **VOID**: all its confirmed orders → `VOIDED` (ORD-T06); `TierResolved{outcome: VOID}`; `DepositRefundRequested{reason: QUOTE_VOID}` per order; buyers are notified with the reason "Fewer than {min} buyers confirmed for this dealer's offer, so it could not go ahead. Your ₹999 test deposit is being refunded."
3. Else → **RESOLVED**: `tier = tier_for(n)`; final snapshot = engine(config, tier.discount); invariant `final.cash_payable ≤ ceiling.cash_payable` (asserted; a violation aborts the transaction and alarms — it means the tier ladder validation was bypassed). Orders → `BOOKED`, bookings issued (ORD-T07).
4. Unconfirmed `CONFIRMATION_PENDING` orders → `DECLINED (MISSED_CONFIRMATION_DEADLINE)` (ORD-T05).
5. Emit one `BookingsIssued{pool_id, bookings_count, void_quote_count}`.

**Per-variant counting:** the tier count is per quote (all variants of one quote count together) unless the dealer marks tiers per variant; P0 default: per quote (Q-032). `min_fulfilment` and `max_units` are per quote.

## 5. VOID handling and the single re-round
- Every affected deposit is refunded automatically (deposit-contract.md, reason `QUOTE_VOID`).
- If the pool ends with **zero bookings** and at least one quote was VOID, and `round_set = 1`, and a full second cycle still fits before `purchase_window_end`, the pool re-opens **once** (POOL-T09 → FORMING, `round_set = 2`); members must commit again with a new deposit (their first deposit was already refunded) (Q-007).
- Otherwise the pool ends `EXPIRED` with reason `ALL_QUOTES_VOID` or `NO_CONFIRMATIONS` (POOL-T10, Q-009).
- If some quotes resolve and others VOID, the pool proceeds to BOOKING; buyers of the VOID quote are refunded and offered similar pools.

## 6. Post-booking cancellations do not change price
Once resolved, the tier and every buyer's final price are fixed. Later cancellations (buyer, dealer, stock) cancel only that booking (refund per deposit-contract.md); they never re-price others. Vector T23: 7 confirm, all 7 cancel after the deadline → tier for 7 still applies to every booking until each is cancelled. The quote builder shows dealers: *"If buyers cancel after booking, the price you set for the confirmed count still applies to everyone else. Set tiers you can honour."*

## 7. Vector index (tiers.json)
Boundaries T01–T06, T29 · below minimum T07, T11, T14 · at/above minimum T08, T09, T15 · zero confirmed T10, T11, T13, T30 · single tier T12–T15 · gap T16 · overlap T17 · rising price T18 · bounded last tier T19 · bad minimum T20, T21 · equal prices T22 · cancellations after deadline T23 · cancellations before deadline T24, T30 · commit exactly at deadline T25 · deposit not captured T26 · capacity T27, T28.
