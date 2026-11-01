# Convoy demo video: script (3–4 minutes)

Recorded against the live site (https://convoy-main.duckdns.org) by `web/scripts/record-demo.mjs`: Playwright video at
1280×720, slow deliberate steps, on-screen captions; the waits for the simulated dealers are sped up in the edit
(marked "sped up" on screen). Narration is added afterwards. Every number said aloud comes from
[EVIDENCE.md](EVIDENCE.md), with its label.

| # | Time | On screen | Narration (suggested) |
|---|---|---|---|
| 1 | 0:00–0:20 | Title card: "Convoy — buy the same car together; the price can only fall" | People who want the same car in the same city rarely get a group price, and dealer quotes are hard to compare: road tax, insurance and add-ons are bundled differently. Convoy pools buyers, makes dealers compete with sealed, itemised offers, and guarantees the price a buyer confirms is the most they can pay. |
| 2 | 0:20–0:40 | Discover, then a model page with the on-road estimate | Prices come from cited sources; the on-road estimate is computed by one price engine, written once in Java and once in TypeScript, checked against the same 84 vectors (laptop + CI). |
| 3 | 0:40–1:05 | "Try the demo" → own pool → join → commit with the ₹999 test deposit | A visitor gets a 24-hour sandbox. Joining is free; committing takes a simulated test deposit, never real money. |
| 4 | 1:05–1:40 | Dealer view → the inbox → the quote builder with its live preview → submit sealed | The same engine runs in the browser, so the dealer sees the buyer's exact price while typing. Offers are sealed: no dealer sees another's. The round closes on the database clock at commit, not the request's arrival. |
| 5 | 1:40–2:05 | Buyer view → offers arriving live (sped up) → Compare offers | Offers arrive over a live stream; the page reconciles against the server's snapshot, so a dropped message never shows a wrong count. |
| 6 | 2:05–2:35 | Compare: itemised offers, the tier ladder, "If the count stays at…", accept | Each offer has tiers: the more buyers confirm, the lower the price. A buyer confirms at the ceiling, and the final tier can only bring it down. |
| 7 | 2:35–2:55 | My orders → confirm at most the ceiling → booking voucher (sped up) | After the deadline an orchestrated saga resolves the tier, holds deposits and issues bookings. Crashing it at each of its 4 steps gives the same result (laptop + CI). |
| 8 | 2:55–3:15 | Read-only admin view (synthetic figures, labelled simulated); a card on the audit chain | Operations see fill rates and dealer response, every figure labelled simulated. Every event lands in a hash-chained audit log that a command verifies. |
| 9 | 3:15–3:45 | Architecture diagram, then three hard problems | Eleven services, each owning its own schema, talking through SNS/SQS with a transactional outbox and inbox. Three hard parts: sealed bids with database-clock deadlines; exactly-once effects over at-least-once messaging (six failure tests, MF-1 to MF-6); and the order saga with crash recovery. |
| 10 | 3:45–4:00 | Closing card: link and honest scope | It's live, in test mode, with synthetic dealers. Capacity numbers are laptop numbers; the full AWS profile is parked. The link is on screen. |

Honest-scope lines that must stay in the edit: "test mode, no real money", "synthetic dealers", "sped up" on every
accelerated segment, and "simulated" on the admin figures.
