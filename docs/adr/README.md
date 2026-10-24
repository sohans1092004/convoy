# Architecture Decision Records

Every ADR follows the same template: **Status · Context · Decision · Alternatives considered (stated honestly) · Consequences**. After P0, changing a frozen rule needs either a new ADR that supersedes the old one or an `AMENDMENT-###` entry in [SPEC.md](../../SPEC.md).

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-microservices-vs-modular-monolith.md) | Microservices (11 boundaries, built in waves) vs modular monolith | Accepted |
| [002](ADR-002-sns-sqs-vs-kafka.md) | SNS/SQS vs Kafka (MSK) | Accepted |
| [003](ADR-003-sse-vs-websockets.md) | SSE vs WebSockets | Accepted |
| [004](ADR-004-saga-orchestration-vs-choreography.md) | Orchestrated order saga; choreography elsewhere | Accepted |
| [005](ADR-005-deployment-profiles.md) | Three deployment profiles with explicit claims | Accepted |
| [006](ADR-006-auth-contract.md) | BFF session + gateway-minted internal JWT | Accepted |
| [007](ADR-007-tier-rule.md) | Tier rule: confirm at the ceiling, the price can only fall | Accepted |
| [008](ADR-008-ranking-rule.md) | Published final-round ranking rule | Accepted |
| [009](ADR-009-deadline-rule.md) | Deadline decided by the owner's DB clock at commit | Accepted |
| [010](ADR-010-redis-non-authoritative.md) | Redis is never a source of truth | Accepted |
| [011](ADR-011-one-price-spec-two-implementations.md) | One price spec, Java + TypeScript engines, shared vectors | Accepted |
| [012](ADR-012-money-as-integer-paise.md) | Money as integer paise | Accepted |
