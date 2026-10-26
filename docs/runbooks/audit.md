# Audit log (P5.C)

## Verify the chain
```
docker compose run --rm audit verify-chain
```
Prints `audit chain intact: N rows` (exit 0) or `AUDIT CHAIN BROKEN at seq S: <reason>` (exit 1). A break means a row was
edited or deleted by someone able to disable the append-only trigger (a superuser). Do not "repair" the chain: keep the
database as it is, take a snapshot, and treat it as a security incident (threat-model.md).

The chain cannot show that its newest rows were deleted (what is left is still a valid chain). The reconciliation below catches that.

## Reconciliation (outbox lag and audit count)
Every service exports `outbox_delivered_events{service}` and `outbox_lag_seconds{service}`; audit exports `audit_events{producer}`.
Once the lag has drained, for every producer:

```
outbox_delivered_events{service="X"} - on() audit_events{producer="X"}  == 0
```

Above 0 for more than a few minutes while `outbox_lag_seconds` is low = audit is missing events (check its queues and DLQs:
dlq-redrive.md). Below 0 = audit holds events an outbox no longer has (an outbox row was deleted).
Baseline: audit only receives events published after its queues exist (its first start), so on an existing database the
difference starts at the number of outbox rows delivered before that (seen in P5.C: pool 36 delivered, audit 12). Compare
the change over time, or record each producer's starting difference; on a fresh database both start at 0.
Locally: `docker compose exec -T <service> wget -qO- localhost:8080/actuator/prometheus | grep -E 'outbox_|audit_events'`.
