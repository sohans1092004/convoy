# ADR-001: Microservices (11 boundaries, built in waves) vs modular monolith

**Status:** Accepted (P0, 2026-10-24)

## Context
Convoy targets about 50,000 buyers, about 10,000 concurrent users at festive peaks, deadline bursts (1,000 quote submissions in 120 s, tested as synchronised arrivals) and 10,000 SSE connections (PLAN §3.2). The services have different load shapes:
- catalog is read-heavy and cacheable;
- quote is bursty at deadlines;
- realtime is bound by connection count;
- notification is bound by queue depth;
- payment needs isolation for its secrets and to limit blast radius.

The project is also a portfolio piece. Part of its purpose is to demonstrate distributed-systems correctness: outbox/inbox, sagas and failure isolation.

## Decision
Eleven services, built in three waves (PLAN §4.1). Data ownership is enforced: each service has its own schema and DB user, with no cross-schema grants. Services communicate by events (SNS/SQS with outbox/inbox), plus the small number of synchronous internal calls listed in services.md.

## Alternatives considered
- **Modular monolith:** one deployable, with a schema per module, in-process events, and an outbox for external effects. *This would serve 50,000 users comfortably* on one or two instances with PostgreSQL. It removes network failure modes, eventual consistency, eleven pipelines and most of the AWS cost. Deadline bursts and SSE could be handled by a separate realtime process. For a real small team shipping a product, this is the better choice.
- **Fewer, coarser services** (for example four: edge, marketplace, money, realtime). This means less operational load and still demonstrates isolation of payment and realtime. Rejected because quote and pool have the most interesting independent load and failure behaviour.

## Consequences
- Accepted costs:
  - network failures between services;
  - eventual consistency (counts and replicas lag);
  - duplicated replicas (round deadlines, quote state);
  - operational load (11 images, dashboards, alarms);
  - higher full-profile cost.
- Mitigations:
  - waves, so the first demo needs only wave 1;
  - shared libraries for outbox/inbox and authorisation;
  - contract tests;
  - the lite profile, which runs everything on one host.
- The README and this ADR state plainly that a modular monolith would also have served the target scale.
