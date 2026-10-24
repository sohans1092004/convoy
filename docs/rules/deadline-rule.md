# Deadline rule (PLAN §2.1, ADR-009)

## 1. The rule
A deadline-bound request is accepted **only if the owning service's transaction reads `now() < deadline` from the database clock at commit time.** Gateway arrival time, client time and queueing time are irrelevant. A request queued before the deadline but committed after it is rejected with `409 DEADLINE_PASSED`.

Implementation contract (PostgreSQL 16):
- The deadline check is inside the same transaction as the state change, as the **last statement before COMMIT**, using `clock_timestamp()` (not `now()`, which is transaction start time):
  `UPDATE quote.quotes SET ... WHERE id = :id AND version = :v AND clock_timestamp() < (SELECT deadline FROM quote.rounds WHERE id = :round) RETURNING ...` — 0 rows → re-read to distinguish `deadline-passed` vs `version-conflict`.
- Deadlines are stored as `timestamptz` (UTC). Displayed in IST (`Asia/Kolkata`, UTC+05:30, no DST).
- The window between the guarded UPDATE and COMMIT is a few milliseconds; a commit that lands after the deadline because of that window is accepted (it read `clock_timestamp() < deadline` inside the transaction). This bounded skew is documented and measured in W4 (`p99(commit_ts − check_ts)`).
- Deadlines are immutable once set (Q-006). Owning services: forming/sealed/final/decision deadlines → pool (quote enforces round deadlines with an immutable replica); confirmation deadline → order (replica of pool's value); dealer confirm, appeal, response windows → order.
- Scheduler (EventBridge) is a **wake-up only**; the owning service re-checks state and DB time; a 60 s sweeper is the backstop.

## 2. Error contract
```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
{
  "type": "https://convoy.example/problems/deadline-passed",
  "title": "The database clock at commit time was at or past the deadline",
  "status": 409,
  "code": "DEADLINE_PASSED",
  "detail": "This round closed at 18:00:00 IST. Your submission reached our database at 18:00:00.412 IST.",
  "deadline": "2026-11-20T12:30:00Z",
  "server_time": "2026-11-20T12:30:00.412Z",
  "aggregate": {"type": "round", "id": "…"},
  "instance": "/dealer/quotes/…/submit"
}
```
`server_time` is the DB clock read in the failed transaction. Idempotent retries of a request that *did* commit in time return the stored success, even after the deadline.

## 3. Client countdown and clock-offset method
- Every API response carries `Date` and a `Server-Time` header (ms precision, from the DB clock via the service; gateway copies it). SSE heartbeats (every 15 s) carry `server_time`.
- Client estimates offset NTP-style on each snapshot fetch: `offset = server_time − (t_send + t_recv) / 2`, keeps the median of the last 5 samples, discards samples with RTT > 2 s. Countdown = `deadline − (Date.now() + offset)`.
- UI: countdown shows server time explicitly ("Closes 18:00 IST · server time 17:58:41"). In the last 60 s: "Submissions must *reach our database* before 18:00:00 IST. Slow networks can make a last-second submit miss." In the last 10 s the submit button stays enabled (we never pre-reject on client time) but shows the warning inline.
- On `409 deadline-passed` the UI shows the conflict state: what happened, the server time, that nothing was saved, and the next step (e.g. "Your last saved version (v3, 17:52 IST) is the one buyers will see").
