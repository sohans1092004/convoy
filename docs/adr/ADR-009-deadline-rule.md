# ADR-009: Deadline decided by the owning service's DB clock at commit

**Status:** Accepted (PLAN §2.1; deadline-rule.md)

## Context
Thousands of submissions can arrive in the last seconds before a deadline. Gateways, clients and queues have different clocks and delays. The outcome must be deterministic and testable: zero commits after the deadline, and zero loss of commits made before it.

## Decision
- A request is accepted only if the owning service's transaction reads `clock_timestamp() < deadline` (the PostgreSQL server clock) in its last guarded statement before commit.
- Schedulers only wake services up; they never decide.
- Deadlines are immutable once set.
- Services that enforce a deadline they don't own use an immutable replica of it.

## Alternatives considered
- **Gateway arrival time:** fair to the client's network, but it requires trusting or forwarding a timestamp and holding the request. Any queueing after the gateway makes "accepted" ambiguous.
- **Client timestamp:** trivially forgeable.
- **A grace window (for example +5 s):** softens the UX but only moves the cliff; it doesn't remove it.
- **A dedicated deadline service or distributed lock:** a central dependency that still needs one clock.

## Consequences
- Requests queued before the deadline can still be rejected. The UI warns in the final minute and shows server time.
- A few-millisecond window between the guarded statement and COMMIT is accepted and measured in W4.
