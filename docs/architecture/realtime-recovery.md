# Realtime (SSE) recovery design (PLAN §4.5, ADR-003)

## 1. Streams
| Stream | Audience | Content |
|---|---|---|
| `/realtime/pools/{id}/stream` | committed/interested buyers of the pool; staff of invited dealerships | pool state + counts (`pool.version`), quote list changes (buyers), round deadlines |
| `/realtime/dealer/stream` | dealer staff | inbox changes, own quote state, **own rank** (`RankChanged` filtered to `dealership_id`), bookings |
| `/realtime/me/stream` | a buyer | own membership, orders, payment status, refunds, disputes |

Authorisation is checked at connect (JWT from gateway) and re-validated every 30 s; `SessionRevoked`/`StaffRevoked` drops connections immediately.

## 2. Message format
```
event: pool.changed
id: pool:3f1c…:42
data: {"aggregate":"pool","id":"3f1c…","version":42,"server_time":"2026-11-20T12:29:41.120Z","delta":{"committed_count":17,"interested_count":31}}
```
- Every message carries the **aggregate type, id and version**. Deltas are hints; the snapshot is the truth.
- Heartbeat comment every **15 s**: `: hb 2026-11-20T12:29:45.000Z` (also used for clock offset, deadline-rule.md §3).
- Client rule: apply a delta only if `version == local.version + 1`; if `version <= local.version` ignore; if a gap is detected → fetch snapshot.

## 3. Backpressure
- One bounded queue per connection (256 messages or 256 KB). When full, the server **closes the connection** with a final `event: overloaded` (best effort). It never buffers unboundedly (W5 slow-consumer test).
- Messages for the same aggregate may be coalesced in the queue (keep latest version per aggregate) before the bound is hit.

## 4. Reconnect and resume
1. On disconnect the client reconnects with exponential backoff + full jitter (0.5 s base, 30 s cap); the server sends `retry:` hints that spread a reconnect storm (W5: 10,000 reconnects).
2. After connecting, the client calls the **owner's snapshot endpoint** (`GET /pools/{id}`, `GET /orders/{id}`, `GET /dealer/quotes/{id}` …) and replaces local state, then applies only stream messages with version > snapshot version. `Last-Event-ID` is accepted but **not relied on**: Redis Pub/Sub has no replay.
3. Correctness never depends on realtime or Redis: missing a message only delays the UI until the next message (gap → snapshot) or the next poll.

## 5. Degraded modes
| Condition | Behaviour |
|---|---|
| realtime down / SSE blocked by proxy | UI falls back to polling the snapshot every **10 s** (5 s in the last 2 min before a deadline the user is acting on); banner "Live updates paused — refreshing every 10 s" |
| Redis Pub/Sub down | realtime tasks share one SQS queue (competing consumers), so a task can only push the events it consumed itself to its own connections; clients on other tasks miss deltas, detect the gap on the next message or heartbeat-triggered check (heartbeats carry the latest known version per subscribed aggregate) and fetch a snapshot; polling fallback if gaps persist |
| SQS lag | messages arrive late; version rule discards stale ones; snapshot on reconnect |
| Wave 1 (before realtime exists) | polling only |

## 6. Fan-out path
Owner outbox → SNS → SQS queue per realtime task group → realtime task → Redis Pub/Sub channel `agg:<type>:<id>` → every realtime task with subscribers → per-connection queue. Target: p95 owner-commit → client delivery < 2 s under W5 (measured, reported including misses).
