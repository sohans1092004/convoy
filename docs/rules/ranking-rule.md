# Ranking rule in the final round (PLAN §2.4, §10.3, ADR-008)

Published identically to all dealers in the quote builder, beside the rank.

## 1. Reference configuration
For each pool and each variant the pool accepts:
- the exact variant (fuel, transmission, trim) and the pool's city/state;
- **mandatory items only**: ex-showroom, discount, registration (dealer figure or estimate), dealer-required accessories, fees, other charges (questionable ones included);
- **baseline insurance**: comprehensive year-1 OD + 3-year TP — the dealer's own figure for that cover if supplied, else the catalogue estimate (Q-031);
- excluded: optional accessories, insurance add-ons, extended warranty;
- tier: the dealer's **ceiling tier** (tier containing `min_fulfilment`) — the price every confirming buyer is guaranteed not to exceed (Q-034).

Score = engine `cash_payable_paise` with `mode = RANKING` for that configuration (vector P029 shows the mode).

## 2. Rank per variant
- Quotes covering the variant and in state SUBMITTED/REVISED (during the final round) or FINAL are ranked ascending by score.
- **Ties:** (1) earliest promised delivery date for that variant (from the stock claim; no date sorts last), then (2) earliest `committed_at` of the quote version being ranked (the current version), then (3) quote id (deterministic, never shown).
- Rank is computed by quote service at each version commit in the final round and at finalisation (QUO-T04), stored as a snapshot `{pool, variant, quote, rank, of_n, computed_at}`.
- Sealed round (round 1): no rank is shown to anyone.

## 3. What a dealer sees
- Its own rank per variant: "**2nd of 4** for Creta SX(O) Turbo DCT" plus the rule text above.
- Never: competitors' prices, scores, line items, delivery dates, names, the gap to the next rank, or the number of buyers who accepted competitors.
- `of_n` (number of ranked quotes for that variant) is shown because it is needed to interpret the rank; Q-035 records this choice.
- API responses for rank contain only `{variant_id, rank, of_n, rule_version, computed_at}`; authz tests AZ-RNK-01..03 assert no other fields leak.

## 4. What buyers see
Buyers compare full offers (not ranks) on the Compare screen, sortable by cash payable, net cost, delivery date and evidence strength.
