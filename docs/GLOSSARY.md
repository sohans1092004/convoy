# Glossary

| Term | Meaning in Convoy |
|---|---|
| **Pool** | A group of buyers who want the same model in the same city. Pools are model-level; quotes name exact variants. |
| **Interested / committed** | Interested buyers have saved the pool and paid no deposit. Committed buyers have paid the ₹999 test deposit (capture confirmed by webhook). The two counts are shown separately. |
| **Round** | A quoting window with an immutable deadline. Round 1 is **sealed**: dealers never see each other. Round 2 is a single **best-and-final** round, where each dealer sees its own rank. |
| **Quote / quote version** | A dealer's itemised offer for named variants. Each submit or revise creates an immutable version. |
| **Tier** | A range of confirmed-buyer counts (inclusive bounds) with a discount. The price can only fall as the count rises. |
| **Minimum fulfilment** | The smallest confirmed count at which the dealer commits to honour the quote. Below it the quote is **VOID**. |
| **Ceiling price** | The price of the tier containing the minimum fulfilment: the most a confirming buyer can pay. |
| **Acceptance** | A buyer's non-binding choice of one FINAL quote and variant during DECISION_OPEN. |
| **Confirmation** | A buyer's commitment to buy at the ceiling or lower, recorded before the confirmation deadline. |
| **Confirmed count** | The number of confirmations (with captured deposit) committed before the confirmation deadline and not cancelled before it. It decides the tier. |
| **Resolution** | The order service's single transaction per pool at the confirmation deadline. It picks the tier, voids quotes below their minimum, and issues bookings. |
| **Booking / voucher** | The dealer-facing record of a resolved purchase. The voucher is a signed QR token scanned at delivery. |
| **Deposit** | The ₹999 Razorpay test-mode payment that makes a buyer committed. It is a refundable commitment token, never paid to the dealer, and ends either REFUNDED (purchase did not go ahead) or RELEASED (returned after delivery); both return the full amount (AMENDMENT-003). |
| **Cash payable** | The sum of all line items the buyer pays at purchase, including TCS. |
| **Estimated net cost** | Cash payable minus creditable TCS, with an explanation. |
| **TCS** | Tax collected at source: 1 % of the vehicle consideration when it exceeds ₹10 lakh (Income-tax Act 2025 s.394(1) Sl. 6; formerly s.206C(1F)). |
| **Evidence level** | Each line is DEALER_CONFIRMED, CATALOGUE_ESTIMATE or UNVERIFIED. The level is rule-based, not a score. |
| **Ruleset version** | The version stamp of the price rules (RTO, TCS, insurance, metadata) used for a calculation. |
| **Snapshot** | A stored calculation: inputs, ruleset version, output, timestamp and SHA-256 of canonical JSON. |
| **Reference configuration** | Mandatory items only plus baseline insurance at the ceiling tier. It is the basis for ranking. |
| **Outbox / inbox** | Transactional event publishing and consumer dedupe (PLAN §4.3). |
| **Aggregate version** | A monotonically increasing version of an owner's aggregate, carried on every event and SSE message. |
| **Deadline rule** | Accepted only if the owner's DB clock at commit reads `now < deadline`. |
| **Profile** | A deployment profile: local, lite (always-on demo) or full (on demand). |
| **Simulated** | The label on any metric derived from synthetic dealers or buyers. |
| **Paise** | 1/100 rupee. Every amount in Convoy is stored as integer paise. |
| **Lakh / crore** | Indian number grouping: ₹1,00,000 = 1 lakh; ₹1,00,00,000 = 1 crore. Used for display only. |
| **IST** | India Standard Time, UTC+05:30, with no daylight saving. Used for display; storage is UTC. |
