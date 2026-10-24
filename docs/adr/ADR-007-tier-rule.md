# ADR-007: Tier rule — confirm at the ceiling; the price can only fall

**Status:** Accepted (PLAN §2.2, §10.5; tier-resolution.md)

## Context
Tiered group prices are circular if the price depends on how many buyers commit while buyers decide based on the price. A buyer must never be surprised by a higher price.

## Decision
1. Each quote has contiguous tiers and a minimum fulfilment count.
2. Accepting a quote is non-binding.
3. At confirmation, each buyer gets an exact **ceiling**: the price of the tier that contains the minimum. They confirm "this price or lower".
4. The tier count is the number of confirmed buyers. A buyer counts only if their confirmation and captured deposit are committed before the confirmation deadline, and they have not cancelled before it.
5. Everyone pays the resolved tier price, which is always ≤ the ceiling.
6. Below the minimum, the quote is VOID: deposits are refunded automatically and the pool may re-round at most once.
7. Cancellations after booking never re-price the other buyers.

## Alternatives considered
- **Price at the acceptance count:** acceptances are non-binding and can be inflated, and buyers drop out after seeing the price.
- **Binding commitment at an unknown final price:** gives dealers the most certainty, but the buyer risk is unacceptable and is likely unlawful as consumer UX.
- **Iterative rounds until the price settles:** confusing, slow and gameable.
- **Re-pricing after post-booking cancellations:** shifts dealer risk onto buyers, and the remaining buyers' price would rise as the count drops, breaking "price can only fall".

## Consequences
- Dealers carry the risk of post-booking cancellations, and the quote builder says so.
- Gaps in tier ladders are rejected, which keeps reasoning simple. When a quote sets a capacity (`max_units`), confirmations are first-come, first-served.
