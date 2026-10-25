# Runbook: a dead-letter queue is not empty (alarm `DLQ_NOT_EMPTY`)

Every consumer queue has a DLQ (`<queue>-dlq`, `maxReceiveCount` 5, event-catalogue.yaml header). A message lands there
after five failed attempts: the handler threw for it every time (a poison message, MF-5 in
[messaging-failure-tests.md](../testing/messaging-failure-tests.md)). Locally the alarm is the log line
`ALARM DLQ_NOT_EMPTY <dlq-url> has N message(s)` and the `dlq_depth{queue}` metric (checked every 30 s); in AWS it is a
CloudWatch alarm on the DLQ's visible-message count.

## What it means for buyers and dealers
- Standard queue: other events keep flowing; only the failed event's effect is missing.
- FIFO queue (pool events): the failed message's group (one pool) was blocked until the message left for the DLQ, then
  the group continued. The skipped event's effect for that pool is missing until the redrive.
- Nothing was half-applied: the handler's transaction rolled back every time, and no inbox row was written.

## Steps
1. **Find the cause.** Search the consumer's logs for `handler failed for message` with the message id; the exception
   names the event type and the reason. Read the message without consuming it (it becomes visible again after 30 s):
   `aws sqs receive-message --queue-url <dlq-url> --attribute-names All --message-attribute-names All --visibility-timeout 30`
   (locally: `docker compose exec localstack awslocal sqs receive-message ...`).
2. **Fix it.** Deploy the code or data fix. Do not redrive first: the message would fail five more times and return.
3. **Redrive.** Move the messages back to the source queue:
   - From code (what the MF-5 test runs): `Topology.redrive(dlqUrl)` receives each DLQ message, sends it to the source
     queue (FIFO: same message group, a new deduplication id) and deletes it from the DLQ.
   - With the CLI, the same three steps per message: `receive-message` on the DLQ, `send-message` to the source queue with
     the same body and message attributes (FIFO: `--message-group-id <group> --message-deduplication-id <id>-redrive`),
     then `delete-message` on the DLQ with the receipt handle.
4. **Confirm.** The DLQ depth returns to 0 and the alarm clears. The event is applied exactly once even if part of it had
   been applied before: consumers dedupe on `event_id` (inbox) and ignore stale versions.

## Do not
- Delete DLQ messages to silence the alarm: the effect (a refund, a count) would be lost. If an event truly must be
  dropped, record why in the incident notes first.
- Edit message bodies by hand: the owner's outbox is the source of truth; republish from the outbox instead.
