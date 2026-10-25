# Messaging failure tests MF-1…MF-6: results (P2)

Environment: local, Windows 11 + Docker Desktop (WSL 2, 10 GB), Testcontainers PostgreSQL 16.15 + LocalStack 4.14.0
(SNS/SQS). Real `OutboxRelay` and `InboxConsumer`; faults injected through `convoy.platform.Faults` named points.
Queue settings in the tests: visibility timeout 3 s (spec 10 s; 30 s in the stack), maxReceiveCount 5, default
retention (4 days), 4 pollers per queue, long-poll 1 s. Commit: see the P2 report.

| # | Test | Path | Result | Business-level assertion |
|---|---|---|---|---|
| MF-1 | `PaymentMessagingFailureIT.mf1…` | pool → payment (MemberLeft, FIFO) | pass | no refund while unpublished; after relay restart exactly one refund, one RefundRequested, outbox `attempts = 1`, delivery < 5 s |
| MF-2 | `…mf2BrokerAccepted…` | order → payment (DepositRefundRequested, standard topic) | pass | published twice (same event_id); one refund; `inbox_duplicates_total` +1 |
| MF-2 (FIFO) | `…mf2OnTheFifoTopicSnsDeduplicates…` | pool → payment (FIFO) | pass | SNS FIFO dedup (MessageDeduplicationId = event_id) drops the republish: one delivery, one refund, no duplicate to count |
| MF-3 | `…mf3ConsumerCommitted…` | pool → payment | pass | refund committed, crash before ack, redelivered after the visibility timeout, rejected by the inbox key: one refund, duplicates +1 |
| MF-4 | `RealtimeMessagingFailureIT` (2 tests) | pool → realtime (FULL subscriber, FIFO queue, injected out of order) | pass | v8 parked, v7 applied, then v8: the SSE client sees 6 → 7 → 8, never a regression; replayed v6 counted stale and not shown; a gap that never closes resyncs from the owner after 1 s (5 min in the catalogue) and clients get `resync` at the owner's version |
| MF-5 | `…mf5Poison…` | pool → payment | pass | five failures → DLQ (depth 1), `ALARM DLQ_NOT_EMPTY`; another pool's event processed meanwhile; after the fix, the runbook redrive (docs/runbooks/dlq-redrive.md) applies it exactly once; a second redrive moves nothing |
| MF-6 | `MessagingBacklogIT` | pool → notification (standard queue, consumer offline) | pass | 5,000 events (500 pools × 10 versions) all reach the inbox exactly once; applied 5,000, stale 0; every pool ends at its highest version; DLQ empty |

Every MF-1…MF-5 test ends with the global invariants I-1 (delivered, one inbox row), I-2 (one refund, one
RefundRequested), I-3 (every journal entry balances) and I-4 (DLQs empty unless expected).

## MF-6 measurements (5,000 events, CI size)

| Run | Publish (relay) | Drain after restart | Drain rate | Peak backlog depth | Depth rose after peak |
|---|---|---|---|---|---|
| before fixes (1 poller, delete per message, publish per row) | 46.4 s | 154.9 s | 32 /s | 4,990 | 0 times |
| 4 pollers + batch delete | 41.6 s | 37.4 s | 134 /s | 4,960 | 0 |
| + relay PublishBatch (10 per call) | 10.3 s | 38.4 s | 130 /s | 4,960 | 0 |

Oldest-message age is a CloudWatch metric, not an SQS queue attribute, so the test samples queue depth instead (it must
only fall after the peak). The 50,000-event run is recorded in the P2 report.

## Defects these tests found (all fixed)
1. `aggregate_versions` was not keyed by consumer: two consumers in one schema dropped each other's events as stale.
2. A snapshot loader that threw (owner down) failed the message, which on a FIFO queue blocked the whole group.
3. A failing message stopped the rest of its received batch, so an innocent message from another group could be
   dead-lettered with a poison one; now only the failed message's own FIFO group waits.
4. Binding any one `convoy.messaging.*` property zeroed the others (relay interval 0 crashed startup; `enabled` became false).
5. Throughput: one poller, one delete call per message and one publish per row (32 events/s drain, 120/s publish).
