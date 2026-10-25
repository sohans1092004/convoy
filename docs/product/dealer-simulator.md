# Dealer simulator (P3.F)

Convoy has no real dealers yet (PLAN honest limits). The simulator stands in for them in demos and tests. It is code in
`tools/simulator/`, configured by `tools/simulator/dealers.json`, and drives three SYNTHETIC verified dealerships seeded in
Pune for Maruti Suzuki (`dealer` migration V5; staff `sim1..sim3@convoy.test` in `deploy/local/seed/users.json`).

## Rules it always follows
- **Only the public dealer APIs**, through the gateway: email sign-in, `GET /dealer/inbox`, create quote, save draft,
  submit, revise, withdraw, `GET /dealer/quotes/{id}/rank`. No internal endpoints, no database writes; the server checks
  every quote exactly as it checks a human dealer's.
- **Deterministic**: a seeded PRNG (mulberry32) decides every choice, so the same seed gives the same behaviour
  (`node --test tools/simulator/dealer-sim.test.mjs`).
- **Stock-limited inventory** per dealership (variant code, colour, quantity, delivery days): it never quotes a variant it
  holds no stock for, and declares exactly that stock.
- **Margin floor**: `max_discount_bp` of ex-showroom; it never discounts past it, in any tier or round.
- **Verified data only**: the ex-showroom it quotes is the catalogue figure the round carries (verified catalogue rows);
  registration and insurance are left to the published estimates.

## Strategies
| Strategy | Behaviour |
|---|---|
| `aggressive` | Sealed round: a modest discount (25–35 % of its floor). Final round: revises to within 0.5 % of its margin floor, undercutting the field. |
| `conservative` | One modest discount (40–60 % of its floor); never revises. |
| `slow_responder` | Prepares its quote, then submits 8–12 s before the sealed deadline (the DB clock still decides). |
| `withdraws` | Submits, then withdraws before the deadline (QUO-T05). |
| `misprices_addon` | Adds a dealer-required accessory (`MUDFLAPS`) at `addon_price_paise`; the buyer's compare screen flags a dealer-required add-on that the other offers do not require. |

The seeded dealerships run `aggressive` (sim1), `misprices_addon` (sim2) and `conservative` (sim3); any strategy can be
assigned in `dealers.json`. Run against a pool: `node tools/simulator/dealer-sim.mjs <pool-id> [seed]`. The nightly
three-dealer journey (`THREE_DEALERS=1`, `e2e/journey.spec.ts`) uses sim1 and sim2 next to a Playwright-driven dealer.
