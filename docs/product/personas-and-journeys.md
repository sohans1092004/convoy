# Personas and journeys

These personas are illustrative and were written for design work. They are not research findings: the real usability beta (PLAN §1, 20–50 people) will validate or replace them.

## Personas
| Persona | Context | Goals | Worries | Screens that serve them |
|---|---|---|---|---|
| **Priya, first-time buyer (Pune)** | Salaried, 29. Comparing Creta and Seltos, budget about ₹16 lakh on-road, buys within 2 months. | A fair price without haggling, and clarity on what "on-road" includes. | Hidden charges (handling, forced accessories), being pressured, losing the deposit. | Discover, Join, Compare, Confirm |
| **Rahul, family upgrader (Mumbai)** | 41, replacing a hatchback with an MPV (Ertiga). Mostly uses a phone. | Delivery before a festival date, and a trustworthy dealer. | Delivery delays and stock vanishing after booking. | Compare (delivery, stock evidence), Track, Dispute |
| **Meera, dealer sales staff (Baner Motors, synthetic)** | Handles quotes for 3–4 pools a week. | Win volume without giving away margin, and know where she stands in the final round. | Tiers that she can't honour, and competitors seeing her prices. | Quote builder, bookings |
| **Arjun, dealer admin** | Owns the dealership profile. | Verified status, staff control and analytics for his own dealership. | Staff leaving while still having access. | Onboarding, staff invite/revoke |
| **Platform admin** | Convoy operations. | Resolve disputes, keep refunds flowing, catch abuse. | Mistakes involving money, and admin overreach. | Admin console (P5) |

## Core journey (P1 walking skeleton, then hardened in P2–P4)
1. **Discover:** Priya filters by Pune + petrol + automatic, compares Creta variants side by side, sees on-road estimates with source dates, and saves the Creta.
2. **Join:** she sees a Creta pool in Pune (6 of 8 committed, 31 interested). She reads the pre-commit terms, ticks the acknowledgement, and commits with the ₹999 test deposit. The status stays pending until the webhook confirms the capture.
3. **Quote rounds:** 4 dealers are invited. A sealed round runs, then a best-and-final round, and Meera's quote builder shows her own rank.
4. **Compare:** Priya sorts the three FINAL offers by cash payable, expands the breakdowns, and notices that one dealer requires mud flaps. She accepts Baner Motors (non-binding).
5. **Confirm:** she sees the exact ceiling of ₹16,26,629 and the live confirmed count, and confirms before Tue 24 Nov 6:00 PM IST.
6. **Resolution:** 6 confirm, so tier 5–9 applies. The booking and voucher are issued and the deposit is applied.
7. **Track:** the dealer confirms, schedules delivery on 5 Dec, and scans the voucher at handover. Priya enters her invoice, sees her realised saving, and reviews the dealer.
8. **Unhappy path:** delivery slips past 5 Dec + 7 days. Priya is prompted to open a support case with evidence, and the admin resolves it. If the outcome is a cancellation, she gets a full refund, and other buyers' prices are unchanged.

## Journey edge cases each phase must cover
- Minimum not reached → EXPIRED, refund, similar pools offered (P2).
- Join conflicts at the sealed deadline; double-click commit (P2).
- Dealer revises a quote while a buyer is viewing it (stale state); dealer withdraws before the deadline (conflict state) (P3).
- A confirmation that lands after the deadline (409); an offer VOID below the minimum, followed by the single re-round (P4).
- The dealer doesn't confirm the booking within 48 h → booking cancelled, refund, auto-dispute (P4).
