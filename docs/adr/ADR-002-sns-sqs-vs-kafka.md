# ADR-002: SNS/SQS vs Kafka (Amazon MSK)

**Status:** Accepted (PLAN §10.1)

## Context
Services publish domain events from transactional outboxes. Consumers need:
- at-least-once delivery;
- per-aggregate ordering, but only for pool membership and the round lifecycle;
- DLQs;
- replay for a consumer that was offline for hours (messaging test 6);
- near-zero idle cost (the lite profile is always on; the full profile runs on demand).

## Decision
- One SNS topic per owning service, plus an SNS FIFO topic for pool events.
- Topics fan out to one SQS queue per consumer: standard queues, with FIFO only where justified.
- Each queue has a DLQ with alarms and a redrive runbook.
- Consumers check `aggregate_version`.
- Replay beyond SQS retention (maximum 14 days) comes from the owner's outbox table, which is kept for 30 days, through an admin re-publish job.

## Alternatives considered
- **Kafka / MSK:**
  - *Advantages:* a durable ordered log, consumer-group replay from any offset, high throughput and a schema-registry ecosystem. Per-aggregate ordering would come from partition keys, which is a genuine advantage.
  - *Costs:* brokers bill hourly even when idle, and so does Serverless MSK through per-cluster-hour charges. It adds operational surface (partitions, rebalancing, retention).
  - *Fit:* our throughput does not need it; the peak is an average of about 56 notifications/s during bursts.
  - Kept as a written comparison, not run (PLAN §10.1).
- **EventBridge bus:** content-based routing plus archive and replay, but it charges per event and has no FIFO. Used only for deadline wake-ups (EventBridge Scheduler).
- **PostgreSQL LISTEN/NOTIFY or a DB-backed queue:** the simplest option, with no AWS dependency. However, it couples every service to one database for messaging and weakens the isolation demonstration.

## Consequences
- FIFO throughput limits per topic and queue are adequate for pool events at our scale. High-throughput FIFO mode can be enabled if W2/W3 show otherwise.
- There is no log-based replay, so test 6 relies on SQS retention plus outbox re-publish. This is documented.
- Messages are limited to 256 KB, so events carry ids and small payloads; larger data is fetched through owner APIs.
