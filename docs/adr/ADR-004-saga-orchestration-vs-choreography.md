# ADR-004: Orchestrated order saga; choreography elsewhere

**Status:** Accepted

## Context
After the confirmation deadline, several services must act for each confirmed buyer:
- resolve tiers;
- issue bookings;
- apply deposits (payment);
- reserve stock (quote);
- compensate on failure: cancel the booking, refund, open a dispute, hit the dealer's reliability score.

Other cross-service flows are simple reactions, for example refunding when a member leaves.

## Decision
The **order service orchestrates** the post-confirmation saga. Its steps are persisted in `order.saga_steps` (APPLY_DEPOSIT, RESERVE_STOCK; with status, attempts and next_attempt_at), and compensations are explicit. All other cross-service flows use **choreography**: services react to the events they subscribe to.

## Alternatives considered
- **Choreography everywhere:** fewer central components. But the booking flow's state would be spread across order, payment and quote; answering "where is this booking stuck?" would need correlation across three services; and compensations would become implicit.
- **Orchestration everywhere, or a workflow engine such as Step Functions or Temporal:** uniform visibility, but central coupling even for simple reactions, plus an extra platform to run and pay for.
- **Step Functions for the order saga only:** managed retries and visual history. However, it adds per-transition cost and splits saga state between AWS and the order DB. Rejected to keep saga state transactional with the order rows.

## Consequences
- order is the single place that answers "what happened to my booking" (the timeline).
- Saga steps are idempotent (keyed by order_id + step) and retried with backoff. Stuck steps raise an alarm and appear in the admin queue.
- W7 tests the saga under downstream failures, using Toxiproxy on payment and quote.
