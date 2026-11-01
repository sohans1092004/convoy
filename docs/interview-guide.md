# Interview guide

Short, accurate answers with pointers to code and evidence. Numbers come from [EVIDENCE.md](EVIDENCE.md) and keep
their label (laptop, CI, live lite host). Say "laptop" when it was a laptop.

**1. Why microservices for a project this size?**
The domain has clear ownership boundaries (pools, quotes, orders, payments) and the point was to practise the hard parts
honestly: data ownership, messaging, a saga. Each service owns one schema and DB user with no grants elsewhere, checked
in CI. The cost is real (more moving parts, eventual consistency), and the ADR says so.
→ [ADR-001](adr/ADR-001-microservices-vs-modular-monolith.md), [architecture/services.md](architecture/services.md)

**2. SNS/SQS rather than Kafka?**
At this scale a managed queue per consumer with DLQs, filter policies and redrive is cheaper and simpler to operate
than a Kafka cluster; replay and long retention were not requirements. Pool events go through a FIFO topic for
per-pool order. → [ADR-002](adr/ADR-002-sns-sqs-vs-kafka.md)

**3. How do you get exactly-once effects?**
Transactional outbox (the event row commits with the business change; a relay publishes it) plus an inbox per
consumer (`(consumer, event_id)` recorded in the same transaction as the effect). Delivery is at least once; effects
are once. Six named failure tests, MF-1..MF-6, cover crash before publish, duplicate delivery, crash before ack,
out-of-order, a poison message and an offline consumer; all pass (laptop + CI).
→ [testing/messaging-failure-tests.md](testing/messaging-failure-tests.md), `libs/platform`

**4. Stale-version consumers vs dedupe-only ("EVENTS") consumers?**
Most consumers keep state per aggregate and drop an event whose version is not newer (FULL subscribers park a gap and
fall back to a snapshot). Notification and audit store every event as its own message, so dropping an older version
would lose a message: with several pollers a backlog replays out of order. Those use dedupe-only subscriptions.
→ SPEC.md AMENDMENT-010 and -012, [architecture/event-catalogue.yaml](architecture/event-catalogue.yaml)

**5. Tiered pricing is circular. How did you fix it?**
Price depends on how many confirm, and people confirm based on the price. The fix: a buyer confirms at an exact
ceiling (the tier containing the dealer's minimum, below which the offer is void), and the final tier can only lower
it. The count is confirmations committed before the deadline. 30/30 tier vectors pass through the order service
(laptop + CI). → [ADR-007](adr/ADR-007-tier-rule.md), [rules/tier-resolution.md](rules/tier-resolution.md)

**6. Who decides whether a request beat the deadline?**
The database clock of the owning service, as the last statement before commit (`clock_timestamp() < deadline`), not
the gateway or the client. 50 parallel submissions around a deadline: every early one kept, every late one refused
with nothing stored (laptop + CI). → [ADR-009](adr/ADR-009-deadline-rule.md), [rules/deadline-rule.md](rules/deadline-rule.md)

**7. How do sealed bids and ranking work?**
Offers are invisible to other dealers; the ranking rule is published and computed with the same price engine at the
ceiling tier, with explicit tie-breaks. Dealers only ever see their own rank and the field size.
→ [ADR-008](adr/ADR-008-ranking-rule.md), [rules/ranking-rule.md](rules/ranking-rule.md)

**8. How do you keep the browser and server prices identical?**
One spec, an executable Python reference, shared vectors; a Java library and a TypeScript package must each reproduce
all 84 vectors byte for byte, including a snapshot hash (laptop + CI). Money is integer paise.
→ [ADR-011](adr/ADR-011-one-price-spec-two-implementations.md), [ADR-012](adr/ADR-012-money-as-integer-paise.md)

**9. What invariants protect the money?**
A double-entry ledger in the payment service, checked after every payment test: every entry balances, posted rows are
immutable (a trigger refuses updates), one refund per deposit, each terminal deposit has exactly one full terminal
entry. 27/27 deposit outcomes pass (laptop + CI). Manual money actions need a second admin (AMENDMENT-001).
→ [rules/deposit-contract.md](rules/deposit-contract.md), `services/payment`

**10. What does the saga do when something fails?**
The order service orchestrates it per pool round: frozen → resolved → deposits held → bookings issued → contacts
released, each step one idempotent transaction. Compensations are explicit: a rejected deposit hold cancels that
booking and refunds; missing stock cancels, refunds and opens a dispute. A crash before or after commit at each of the
4 boundaries ends in the same business result (8 runs, laptop + CI). Crashes are thrown in-process, not process kills.
→ [ADR-004](adr/ADR-004-saga-orchestration-vs-choreography.md), [reports/P4_REPORT.md](../reports/P4_REPORT.md)

**11. SSE or WebSockets?**
Updates flow one way, SSE is plain HTTP through every proxy, and reconnection is built in. The client treats the
stream as hints: on a gap or reconnect it fetches the owner's snapshot. On the laptop: 1,000 streams on 2 instances;
after killing one, 486 of 486 cut streams recovered with a fresh snapshot. → [ADR-003](adr/ADR-003-sse-vs-websockets.md),
[architecture/realtime-recovery.md](architecture/realtime-recovery.md)

**12. What if Redis is lost?**
Nothing authoritative lives there: caches, rate limits, idempotency fast paths backed by DB unique keys, Pub/Sub
fan-out. Losing it costs same-instance-only fan-out and cold caches; clients converge through snapshots. A Redis
FLUSHALL was survived in the two-instance realtime scenario (laptop + CI). → [ADR-010](adr/ADR-010-redis-non-authoritative.md)

**13. And if notification is down?**
Nothing upstream waits for it: buyers still commit and confirm, events queue, and the backlog is delivered once when it
returns (`tools/notification/isolation.sh`). Payment and refund messages ignore user preferences.

**14. Privacy and erasure?**
Emails are encrypted at rest and looked up by hash. A data export collects each service's part into one file with
short-lived, audited links. Erasure is tracked until every service confirms; financial rows are kept but pseudonymised,
including outbox payloads. → [privacy/](privacy/), [reports/P5_REPORT.md](../reports/P5_REPORT.md)

**15. What did the smoke runs teach you?**
Test the production configuration locally first. The AWS runs found config bugs one per run; a local prod rehearsal
found five more for free, including one that broke every service and one that made every versioned command fail.
→ [how-it-was-built.md](how-it-was-built.md) §2, [reports/P6_REPORT.md](../reports/P6_REPORT.md) §5

**16. Service Connect never worked. How would you debug it?**
Unresolved. The saved logs show services failing to resolve `pgbouncer` and `adot` long after both were running. Next:
check that clients start only after the servers registered (a task gets the namespace's endpoints at start); that each
`client_alias.dns_name` and port equals what clients dial; that pgbouncer and adot publish endpoints with named port
mappings; and if it still fails, use a Cloud Map private DNS namespace with plain A records instead.

**17. How do you know your tests test anything?**
By breaking the code and watching them fail. Audits found 6 vacuous checks in P5 and 24 vacuous Storybook state
stories in P8; all fixed, and the new checks were each shown to fail on purpose. → [EVIDENCE.md](EVIDENCE.md) §1

**18. What would you do next?**
Get the full profile's service discovery working and run the 10,000-SSE and mixed-load tests on AWS (unmeasured today);
the HA drills; move the demo to the Oracle VM when capacity appears; a native review of the Hindi; audit-log pruning;
CI validation for the lite-ec2 Terraform.
