# ADR-008: Published final-round ranking rule

**Status:** Accepted (PLAN §2.4, §10.3; ranking-rule.md)

## Context
The final round should push dealers to their best offer without revealing competitors' values. Revealing them would enable collusion and leak commercial information.

## Decision
- Quotes are ranked per variant by cash payable for the pool's reference configuration: mandatory items only, baseline insurance, at the ceiling tier.
- Ties go to the earliest promised delivery date, then the earliest version commit.
- Each dealer sees only its own rank and the number of ranked quotes.
- The rule text is shown identically to all dealers.

## Alternatives considered
- **No rank (pure sealed bid):** the simplest and leaks the least, but dealers get no signal to improve, so one best-and-final round becomes a guess.
- **Show the best price (open auction):** the strongest price pressure, but it reveals values and invites signalling and collusion. PLAN §3.1 excludes live open auctions.
- **Rank by net cost (after the TCS credit):** TCS is creditable, but cash is what buyers pay now. Ranking by net cost would change the order only near the TCS threshold, and cash payable is simpler to explain.
- **A weighted score (price + delivery + rating):** opaque and contestable.

## Consequences
- `of_n` reveals the number of competitors per variant (Q-035). Accepted as necessary context for interpreting the rank.
- Ranking excludes optional items, so dealers cannot game their rank through optional accessory prices.
