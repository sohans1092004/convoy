# ADR-010: Redis is never a source of truth

**Status:** Accepted (PLAN §4.2)

## Context
Redis is attractive for counts, idempotency and fan-out. But it can lose data on failover or flush, and it holds state outside the per-service schemas, in a single region or cluster.

## Decision
Redis holds only:
- rate-limit buckets;
- caches (session validation ≤ 30 s, catalogue);
- idempotency fast paths, always backed by a DB unique key;
- Pub/Sub fan-out.

Membership, payments, bookings, counts and deadlines live in PostgreSQL. A drill in P7 flushes Redis under load and verifies that nothing is corrupted.

## Alternatives considered
- **Redis as the counter and lock store for hot pools:** faster, but correctness would depend on Redis durability and failover semantics.
- **No Redis at all:** possible, using Postgres-backed rate limits, in-process caches and SNS/SQS fan-out to every realtime task. It means somewhat more DB load and less efficient fan-out.

## Consequences
- Hot-pool counts rely on PostgreSQL row locks; W3 measures contention on a single pool.
- Flushing Redis causes a latency blip and cache misses, but never wrong state.
