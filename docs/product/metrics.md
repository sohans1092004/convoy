# Marketplace and product metrics (PLAN §3.1(12), §11)

**Labelling rule:** any metric computed from synthetic dealers (simulator) or synthetic buyers carries the label **"simulated"** in the UI, in reports and in the `marketplace_metrics_daily.simulated` column. Beta metrics state their sample size and date range. Simulated and real data are never mixed in one figure.

## Marketplace metrics (audit service read models, P5)
| Metric | Definition | Unit / window |
|---|---|---|
| Fill rate | Pools reaching READY_FOR_QUOTES ÷ pools created, by creation cohort | % per week |
| Time to fill | Median of (PoolReady.occurred_at − PoolCreated.occurred_at) over pools that filled | hours |
| Dealer response rate | Invited dealerships that submitted ≥ 1 quote version before the sealed deadline ÷ invited dealerships | % per round |
| Pools with ≥ 2 valid quotes | Pools whose final round ended with ≥ 2 FINAL quotes ÷ pools that reached QUOTE_OPEN | % |
| Acceptance → confirmation | Orders CONFIRMED (counted at the deadline) ÷ orders created | % |
| Confirmation → delivery | Bookings DELIVERED ÷ bookings issued, cohort closed after promised date + 30 days | % |
| Cancellation rate | Bookings CANCELLED ÷ bookings issued, split by reason (buyer / dealer / stock / admin) | % |
| VOID rate | Quotes VOID ÷ quotes with ≥ 1 acceptance | % |
| Savings by label | Distribution (median, p25, p75) of each savings-model.md label **separately**: dealer-stated discount, difference vs reference, difference vs best other quote, realised saving (only confirmed invoices) | ₹ |
| Refund latency | p50/p95 of (RefundCompleted − RefundRequested) | hours |
| Dispute resolution time | p50/p95 of (DisputeResolved − DisputeOpened) | days |

## Product / UX metrics
| Metric | Definition |
|---|---|
| Journey completion (beta) | Share of beta participants completing discover → join → compare → accept → confirm in a moderated session; n and dates stated |
| Task success and time on task | Per flagship screen, from moderated beta tasks |
| Pre-commit comprehension | Share of beta participants correctly answering 3 questions about the deposit terms after reading the Join screen |
| Error and conflict exposure | Rate of 409 deadline-passed / version-conflict responses shown to users per 1,000 mutations |
| Web Vitals (RUM) | p75 LCP / INP / CLS per route (frontend-quality.md §1) |
| Accessibility | axe violations (serious/critical) = 0, plus a manual pass log |

## Engineering evidence metrics (PLAN §11)
Each of these is reported with the run metadata: deadline correctness (0 late commits, 0 lost), W3 count exactness, ledger invariant checks, webhook replay results, saga crash-recovery outcomes, SSE 10k + reconnect storm, W9 p95/p99, and the full-profile cost per run.
