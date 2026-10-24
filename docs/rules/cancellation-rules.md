# Cancellation rules (PLAN §2.6) — shown in the UI before commitment

## 1. Rules
| Case | Rule | Machine refs | Refund reason |
|---|---|---|---|
| Buyer leaves after committing | Free until CONFIRMATION opens (decision deadline); full refund | MEM-T05 | `BUYER_LEFT` |
| Buyer accepts nothing by the decision deadline | Released; full refund | MEM-T10 | `NO_ACCEPTANCE` |
| Buyer declines or misses the confirmation deadline | Declined; full refund | ORD-T04/T05 | `BUYER_DECLINED` / `MISSED_CONFIRMATION_DEADLINE` |
| Buyer cancels after confirming (test mode) | Full refund; recorded as a buyer cancellation; never changes anyone else's price | ORD-T03, BKG-T06 | `BUYER_CANCELLED` |
| Dealer withdraws a quote | Allowed until the round deadline (audited). After FINAL, withdrawal = dealer cancellation | QUO-T05 / QUO-T06 | — / `DEALER_CANCELLED` |
| Dealer doesn't honour the final price, or stock vanishes | Buyer refunded, booking cancelled, dispute auto-opened, dealer reliability score hit, admin review | BKG-T03, BKG-T06, ORD-T10 | `DEALER_NOT_HONOURED` / `STOCK_UNAVAILABLE` / `DEALER_CANCELLED` |
| Pool doesn't reach its minimum | EXPIRED; refunds; buyers offered similar pools | POOL-T11 | `POOL_EXPIRED` |
| No valid quotes | NO_QUOTES; refunds | POOL-T04/T06/T12 | `POOL_NO_QUOTES` |
| Admin cancels pool | CANCELLED with reason; refunds | POOL-T13 | `POOL_CANCELLED` |
| Offer VOID (too few confirmed) | Refund; pool may re-open once | ORD-T06, POOL-T09 | `QUOTE_VOID` |
| Booked but not delivered by promised date + 7 days | Buyer prompted to open a dispute; admin escalation | BKG-T07/T08, DSP-T01 | — |

Dealer reliability score (dealer service) is decremented on `BookingCancelled` with a dealer reason and on `QuoteWithdrawn{after_final: true}`; the formula is a P3 decision (Q-036).

## 2. Exact pre-commit UI copy (Join a pool screen, above the Commit button)
> **Before you commit — TEST MODE: no real money**
>
> - You pay a **₹999 test deposit** to commit. It holds your place; it is not a booking.
> - **Leave any time before confirmation** (until {decision_deadline, e.g. "Fri 20 Nov, 6:00 PM IST"}) and get the full ₹999 back.
> - If the pool doesn't reach **{min} committed buyers** by {forming_deadline}, it closes and you get the full ₹999 back.
> - If no dealer makes a valid offer, or an offer can't go ahead because too few buyers confirm, you get the full ₹999 back.
> - Later you'll see an exact **maximum price** (the ceiling) and choose whether to confirm. If more buyers confirm, **your price can only fall**.
> - If you confirm and later cancel, you still get the full ₹999 back (test mode). Your cancellation never changes anyone else's price.
> - If a dealer cancels or can't deliver the car at the agreed price, you get the full ₹999 back and we open a support case for you automatically.
> - Refunds go back to the original payment method; in test mode no money moves.
>
> [ ] I've read these terms (version {terms_version})  **[Commit with ₹999 test deposit]**

The checkbox state and `terms_version` are sent with the commit request and stored on the membership (MEM-T02 guard). Hindi copy is a P1 translation of this exact text (next-intl), reviewed by a native speaker before release.

## 3. Copy at confirmation (Accept and confirm screen)
> **Confirm at most ₹{ceiling} (cash payable) — TEST MODE**
> If {next tier min}+ buyers confirm by {confirmation_deadline} the price drops to ₹{next price}. You will never pay more than ₹{ceiling}. If fewer than {min_fulfilment} buyers confirm, this offer can't go ahead and your deposit is refunded. You can cancel after confirming and still get your ₹999 back.
