# Deposit contract (PLAN §2.3)

₹999 (99900 paise, `ruleset.yaml deposit_amount_paise`, configurable per environment), **Razorpay test mode only**. Every payment screen shows **"TEST: no real money"**; test transactions are never presented as real funds or as proof of willingness to buy (PLAN §10.4). Owner: **payment** service. Machines: [`payment.yaml`](state-machines/payment.yaml), [`refund.yaml`](state-machines/refund.yaml); upstream [`membership.yaml`](state-machines/membership.yaml) and [`order.yaml`](state-machines/order.yaml). Contract vectors: [`deposit-outcomes.json`](../../spec-tools/vectors/deposit-outcomes.json) (23 cases).

## 1. Flow
1. Buyer taps **Commit** (pre-commit terms shown — cancellation-rules.md §2). Browser → BFF → gateway → pool `POST /pools/{id}/commitment` with `Idempotency-Key` (client-generated UUIDv4 per click-intent).
2. pool calls payment `POST /internal/deposits` with service token and the **deterministic deposit key** `deposit:{buyer_id}:{pool_id}:{round_set}` (UNIQUE). payment creates the local row (INITIATED), then a Razorpay order `{amount: 99900, currency: INR, receipt: payment_id}`. Same key → same row → same Razorpay order (a double-click yields one order).
3. Browser opens Razorpay Checkout with `order_id`. On return the browser posts the checkout callback; payment verifies `HMAC_SHA256(order_id + "|" + payment_id, key_secret)` and sets PENDING. **The UI shows "Payment received by Razorpay — confirming (usually under a minute)". Never "success".**
4. **Only the signed `payment.captured` webhook sets CAPTURED** (`X-Razorpay-Signature` = HMAC-SHA256 of the raw body with the webhook secret; dedupe on `x-razorpay-event-id`). Auto-capture is enabled on the Razorpay account (pools outlast authorisation holds).
5. pool consumes `PaymentCaptured` → membership COMMITTED (if still committable) → counts update.
6. If no webhook 15 min after PENDING: alarm `STUCK_WEBHOOK`; the sweeper may *read* Razorpay order status for diagnostics but never sets CAPTURED from a poll.

## 2. Idempotency keys
| Call | Key | Store | Retention |
|---|---|---|---|
| Browser → pool commit | `Idempotency-Key` header (UUID) | `pool.idempotency_keys(key, caller, request_hash, response)` UNIQUE(key, caller) | 24 h |
| pool → payment create deposit | `deposit:{buyer}:{pool}:{round_set}` | `payment.payments.deposit_key` UNIQUE | forever |
| payment → Razorpay order | `receipt = payment_id` | Razorpay | — |
| Razorpay → payment webhooks | `x-razorpay-event-id` | `payment.webhook_inbox(event_id)` PK | 90 days |
| Event consumers | envelope `event_id` | `<svc>.inbox(consumer, event_id)` PK | 30 days |
| payment → Razorpay refund | `receipt = refund_id`; lookup-before-create | `payment.refunds.payment_id` UNIQUE | forever |

## 3. Outcome table (every row of PLAN §2.3 → vector IDs)
| PLAN situation | Trigger event | Refund reason code | Vectors |
|---|---|---|---|
| Buyer leaves before CONFIRMATION | `MemberLeft` / `AcceptancesFrozen.released_buyers` | `BUYER_LEFT` / `NO_ACCEPTANCE` | D01, D02 |
| Pool EXPIRED / NO_QUOTES / CANCELLED | `PoolClosed{end_state}` | `POOL_EXPIRED` / `POOL_NO_QUOTES` / `POOL_CANCELLED` | D03–D05 |
| Quote VOID | `DepositRefundRequested` | `QUOTE_VOID` | D06 |
| Quote WITHDRAWN (after FINAL) / dealer cancels | `DepositRefundRequested` | `DEALER_CANCELLED` | D07, D09 |
| Stock unavailable | `DepositRefundRequested` | `STOCK_UNAVAILABLE` | D08 |
| Dealer doesn't honour the price | `DepositRefundRequested` | `DEALER_NOT_HONOURED` | D10 |
| Buyer misses the confirmation deadline | `DepositRefundRequested` | `MISSED_CONFIRMATION_DEADLINE` (explicit decline: `BUYER_DECLINED`) | D11, D12 |
| Buyer cancels after confirming (test mode) | `DepositRefundRequested` | `BUYER_CANCELLED` | D13, D14 |
| `payment.captured` after buyer left / pool closed | webhook on VOIDED_UNPAID or `MemberReleased{LATE_CAPTURE}` | `LATE_CAPTURE` | D15, D16 |
| Duplicate request / duplicate webhook | — | none (no second effect) | D17–D19 |
| *(added)* happy path, refund failure, failed-then-retried, abandoned | — | — | D20–D23 |

A quote withdrawn *before* its round deadline (QUO-T05) refunds nobody by itself: buyers are still in the pool; if it leaves zero valid quotes the pool ends NO_QUOTES (D04).

All refunds are full. `ADMIN_DISPUTE_RESOLUTION` is used when a dispute outcome cancels a booking.

## 4. Reconciliation
- **Nightly (02:00 IST)**: for every payment/refund touched in the last 7 days, fetch Razorpay test-mode state (orders, payments, refunds) and compare: amount, currency, status, refund status. Mismatch classes: `CAPTURED_REMOTE_NOT_LOCAL` (stuck/missed webhook), `LOCAL_CAPTURED_REMOTE_NOT`, `REFUND_STATUS_MISMATCH`, `ORPHAN_RAZORPAY_ORDER`, `AMOUNT_MISMATCH`, `POSSIBLE_DOUBLE_REFUND`. Each creates an admin-queue item; none auto-corrects money state (Razorpay is re-queried and the webhook is redelivered/replayed instead).
- **Hourly**: ledger invariants (below) over all accounts; any violation pages.
- **Daily**: payments in non-terminal states older than thresholds (PENDING > 1 h, REFUND_PENDING > 48 h, APPLIED_TO_BOOKING with booking terminal) listed.

## 5. Ledger
Double-entry journal in `payment.journal_entries` / `payment.journal_lines` (amounts BIGINT paise, `dr` and `cr` columns, one currency INR).

**Chart of accounts**
| Code | Account | Type |
|---|---|---|
| 1100 | Razorpay clearing (test mode) | asset |
| 1200 | Platform bank (manual payouts) | asset |
| 2100 | Customer deposits held | liability |
| 2200 | Deposits applied to bookings | liability |
| 2300 | Dealer settlement payable | liability |

Gateway fees are not modelled in test mode (Q-033); a live system would add `5100 Payment gateway fees` and a receivable.

**Journal entry per event**
| Entry | When | Dr | Cr |
|---|---|---|---|
| J-CAPTURE | PAY-T03 / PAY-T10 | 1100 | 2100 |
| J-APPLY | PAY-T06 | 2100 | 2200 |
| J-SETTLE | PAY-T09 (booking delivered) | 2200 | 2300 |
| J-REFUND | REF-T03 / REF-T05 (refund.processed) | 2100 if refunded from CAPTURED, 2200 if from APPLIED_TO_BOOKING (stored as `refund.source_account`) | 1100 |
| J-REFUND-MANUAL | REF-T06 | source account | 1200 |

**Invariants (checked after every test and hourly)**
1. Every entry: Σdr = Σcr, all amounts > 0, single currency.
2. Posted entries are immutable (DB role has INSERT/SELECT only; UPDATE/DELETE trigger raises). Corrections and refunds are compensating entries.
3. Per deposit: exactly one J-CAPTURE if captured; and **exactly one terminal outcome**: either J-SETTLE (payment SETTLED) or J-REFUND/J-REFUND-MANUAL (payment REFUNDED) — never both, never two refunds (UNIQUE refunds.payment_id).
4. Per deposit at terminal state: balance of 2100 = 0 and balance of 2200 = 0.
5. Global: balance(1100) = Σ captured − Σ refunded via Razorpay, and equals Razorpay test-mode settled-minus-refunded within the reconciliation window.
6. payment.state ↔ ledger agreement: CAPTURED ⇒ 2100 balance = amount; APPLIED_TO_BOOKING ⇒ 2200 balance = amount; SETTLED ⇒ 2300 credited; REFUNDED ⇒ 1100 net 0 for the deposit (or 1200 credited for manual).
