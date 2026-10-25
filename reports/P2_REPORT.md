# Phase 2 report: pools, realtime, passkeys, load

Branch `p2/pools-realtime`, PR #3. CI green on `3a6a094` (all JVM modules, node, e2e). Date 2026-10-25. All numbers come
from runs on one laptop (Docker Desktop, WSL 2 VM with 20 vCPU and 10 GB) unless marked CI. Measurements are in
`reports/p2/*.json|md`.

## 1. Summary

Everything in Step 0 and scopes A–G is built and tested, with these caveats:
- **Real Razorpay refunds are untested.** The test account has refunds disabled. Capture was verified against the real API.
- **W3 counts stayed exact, but capacity didn't hold.** Under 1,000 VUs on one pool, all four counts agreed exactly. On a laptop, 27.6% of requests were refused and join p95 was 41 s.
- **W5 was measured at 1,000 streams, not 2,000.** A 2,000-stream attempt was stopped for low host memory, and in it a realtime instance ran out of heap.
- **The Hindi strings are machine-drafted** and marked as such.

Three open questions were added: Q-043 (passkey policy), Q-044 (what moved for AZ-T6) and Q-045 (no rejoining after leaving a pool).

## 2. Step 0 results

| # | Item | Result |
|---|---|---|
| 1 | Real Razorpay test run | **Capture passed** against api.razorpay.com: order, then `payment.authorized` webhook, then `payment.captured` 0.70 s later, then journal J-CAPTURE. **Refund blocked:** this account has refunds disabled, so the API returns a generic 400 "invalid request sent" (you confirmed). The fake now mirrors this, and a 400 is non-retryable: it goes to REFUND_STUCK and the admin queue. Details: `reports/p2/razorpay-real-run.md`. |
| 2 | Typed DTOs and generated TS client | Done. Records use FAIL_ON_UNKNOWN, OpenAPI is generated per service, and `web/lib/api` is generated from it. Each service's IT fails when the committed spec drifts; this caught the new identity endpoints twice. |
| 3 | Atomic idempotency | Done. The key, the business change and the response share one transaction. A reused key with a different body returns 422 IDEMPOTENCY_KEY_REUSED. A crash-point test shows nothing half-applied. |
| 4 | Version semantics | Done. Every consumer runs a stale check. FULL subscribers park a gap, retry every 1 s, and fall back to a snapshot. The catalogue records the kind, and `validate_spec` checks it. Versions are tracked per consumer (bug fixed in `808b0a2`). |
| 5 | One open pool per key | Done: 50 concurrent creates produce 1 pool (partial unique index plus create-or-find). |
| 6 | AMENDMENT-007 | Done. Q-042: minimum 5 in production, 1 in the demo. Q-041: ₹2,500 (250000 paise) per delivered booking as a dealer receivable. |
| 7 | Honest UI for 501s | Done. Unbuilt actions show what isn't available yet and that nothing changed (journey: booking cancellation returns 501 and is explained). |

## 3. Architecture changes and deviations

- **E012 (revoke staff) moved from P3 to P2.** AZ-T6 needs a revoke action. Invites (E010/E011) stay in P3. (Q-044)
- **New endpoints:**
  - **E151** `POST /auth/passkeys/authenticate/options` (anonymous). WebAuthn needs a server challenge before the assertion. (Q-043)
  - **E152** `POST /internal/sessions/check` (service:realtime). Realtime sends one batch every 15 s and closes revoked streams. (Q-044)
- **New dependencies:**
  - webauthn4j 0.31.11 (Apache-2.0, free). It uses Jackson 3, so identity pins `jackson-annotations` 2.22 (additive; Spring's Jackson 2 is unaffected).
  - The `grafana/k6:2.3.0` image (free) for load tests, run in Docker.
- **Settings changed:**
  - Gateway session cache 30 s → 25 s (contract ≤ 30 s), leaving margin for 1 s polling.
  - Realtime re-check 30 s → 15 s.
  - Identity DB pool 5 → 12. Total across services is 68 of 100 Postgres connections.
- **Gateway:**
  - `/api/dealerships/*/staff/**` routes to identity, not dealer.
  - Idle upstream connections are evicted at 15 s, below Tomcat's 20 s keep-alive.
- **Live updates on the client:** only join and leave deltas (counts only) are merged in place. Every other event refetches the snapshot, because deltas can't carry rounds, the buyer's membership, or state changes reached as a side effect.
- **Local seed data:** two users added to dealership d001: `dealeradmin1@convoy.test` (admin) and `dealer3@convoy.test` (staff to revoke). Reseeding reactivates a revoked seeded staff member.
- **W3 shape:** a buyer can't rejoin after leaving (Q-045). Each VU therefore works through 4 buyers: join (30% as double-clicks), wait, leave for half.

## 4. Test results

- **CI on `3a6a094`:**
  - JVM: catalog, dealer, gateway, identity, order, payment, pool, quote, realtime, platform, price-engine — all pass.
  - Node: unit tests, 24 Storybook state stories and the index check, 68 visual baselines (17 stories × light/dark × 360 px/desktop).
  - e2e: P1 journey at 360 px and desktop with axe, 48 in-app state tests, Hindi switch, passkeys, JS budgets, Lighthouse, realtime two-instance scenarios.
- **Local:** P1 journey 2/2 (4.0 min), state and Hindi tests 50/50, passkey 2/2, identity, realtime and gateway ITs all pass.
- **Messaging failure tests** (`reports/p2/messaging-failure-results.md`):

| Test | What it proves | Result |
|---|---|---|
| MF-1 | Commit, then crash before publish: published after restart, exactly one refund | pass |
| MF-2 | Broker accepted, relay crashed before marking: delivered twice, applied once, duplicate counted | pass (CI flake, see §7) |
| MF-2 FIFO | SNS FIFO deduplicates the republish | pass |
| MF-3 | Consumer committed, crash before ack: redelivered, rejected by the inbox | pass |
| MF-4 | Out of order on a FULL subscriber: v8 parked, v7 applied, then v8 (client sees 6→7→8) | pass |
| MF-5 | Poison message: DLQ after 5 tries, alarm raised, other pools unaffected, redrive after the fix | pass |
| MF-6 | 5,000 events with the consumer offline: all applied exactly once (50,000 locally: publish 67.6 s, drain 118.5 s, about 422/s) | pass |

- **AZ-T6** (`reports/p2/revocation.json`). Each sample is first API refusal, then successes after it, then stream closed:
  - Sample 1 (30 s cache): 27.3 s / 0 / 7.9 s.
  - Sample 2 (25 s cache): 22.3 s / 0 / 11.2 s.
  - Sample 3 (25 s cache): 21.2 s / 0 / 8.6 s.
  - All three pass (limit ≤ 30 s).

## 5. Measurements

**W3** (`reports/p2/w3.json`): 1,000 VUs, 4,000 buyers, one pool, 5 min, start within 10 s. k6 2.3.0 in Docker. Run on `4788f54` plus the fixes now in `6de3f67`.

| Item | Value |
|---|---|
| Counts: pool row / recount / last streamed / baseline + joins − leaves | 1372 / 1372 / 1372 / 1372 (exact) |
| Buyers joined / left / double-clicks | 2,704 / 1,333 / 1,184 (no double memberships) |
| Requests refused or failed | 27.6% (gateway: 1,566 connect timeouts to pool at 1 s; identity: 586 DB-pool timeouts) |
| Join latency p50 / p95 / p99 | 2.0 s / 41.0 s / 52.1 s |
| Leave latency p50 / p95 / p99 | 27 ms / 36.9 s / 45.7 s |
| Stream delivery p95 / p99 | 35.8 s / 36.2 s (one pool means one FIFO group, consumed serially at about 26 events/s); caught up by end of load |

Earlier runs on the way: 97.6% failures, then identity validation made read-only; 72%, then stale connections fixed; 10.6% and 27.6% in later runs. The variance shows this stack sits at its capacity limit.

**W5 local** (`reports/p2/w5.json`): 1,000 streams through the gateway to 2 realtime instances, 20 pools, 2% slow consumers, 5 min hold, realtime-1 killed at half-time. This is not the 10,000 target.

| Item | Value |
|---|---|
| Streams open / events generated | 1,000 / 1,470 |
| Delivery (commit to client) p50 / p95 / p99 | 143 / 238 / 2,565 ms (36,822 samples) |
| Storm: streams cut / recovered with a fresh snapshot | 486 / 486; recovery p95 6.8 s, max 14.3 s |
| Delivery after the storm, p95 | 21.3 s (see §7) |
| Slow consumers cut by the server | 14 of 20 |
| Memory per 1,000 streams | about 180 MiB in realtime and 12 MiB in the gateway (200-stream run). The 1,000-stream run can't measure it: both containers sat at their 360 MB cap. |
| 2,000-stream attempt | 1,859 opened, then stopped for low host memory. realtime-2 hit OutOfMemoryError twice when the storm moved about 1,850 streams onto it. |

Realtime scenarios from scope C (`tools/realtime/two-instances.mjs`, also in CI):
- 4 clients split 2 and 2 across the instances.
- Fan-out p95 38–236 ms.
- Redis FLUSHALL survived.
- Heartbeat continued with Redis down.
- Reconnect after a graceful stop 7.4 s, after a kill 4.4 s.

**JS per route** (gzip; budget = baseline × 1.1, enforced in CI):

| Route | KB | Budget | Starting cap |
|---|---|---|---|
| Discover | 185.5 | 204.1 | 170 (over) |
| Join (model page) | 189.4 | 208.3 | 150 (over) |
| Pool | 189.4 | 208.2 | 170 (over) |
| Compare | 185.3 | 203.8 | 190 |
| Confirm (order) | 194.1 | 213.5 | 150 (over) |
| My cars | 184.5 | 203.0 | 170 (over) |
| Quote builder | 200.9 | 221.0 | 230 |

**Lighthouse** (mobile preset, simulated slow 4G, median of 3, `fda7cd2`):

| Page | LCP | CLS | TBT | Performance |
|---|---|---|---|---|
| Discover | 2,789 ms | 0 | 92 ms | 91 |
| Model | 2,607 ms | 0 | 136 ms | 96 |
| Pool | 2,745 ms | 0 | 129 ms | 95 |

**Revocation:** see §4. API refused at 21–27 s, streams closed at 8–11 s.

**Stack memory with realtime:** the Docker VM uses about 5.0 GB of 9.9 GB at idle with the full stack (2 realtime instances, Redis, LocalStack, Jaeger and the collector). Each realtime instance runs at 330–360 MB against a 360 MB cap. The host had 0.7–2.9 GB free during the tests.

## 6. Bugs found and fixed

1. The pool page stayed on "quoting" after the round closed. Deltas without `state` were merged and bumped the version, so the page never refetched. Found by the P1 journey.
2. An expired session cookie turned public pages into 404s. The BFF and server reads now retry anonymously.
3. A flapping stream caused an endless refetch loop that hid failures; refetches are no longer cancelled.
4. Razorpay 400s were retried forever. They're now non-retryable (REFUND_STUCK, admin queue). `razorpay_order_id` was the string "null".
5. Versions weren't tracked per consumer; a snapshot-loader exception blocked a FIFO group.
6. One failing message dead-lettered the innocent messages in its batch.
7. Messaging property binding zeroed unset fields.
8. Topology `Map.of` ordering broke restarts.
9. A placeholder-row lock race.
10. Messaging throughput: drain 32 → 130/s, publish of 5,000 events 46 → 10 s.
11. Redis publish could block for 60 s; Lettuce reconnect backoff reached 30 s. Fixed with a 2 s timeout and a constant 1 s reconnect.
12. The gateway's connect timeout to realtime was 45 s, so reconnects took 47 s. It's now 1 s, and reconnects take 4–7 s.
13. Authz deny checks found E031 returning 400 and E042 returning 501 to wrong roles. Fixed with `@RequiresRole`, checked before binding.
14. Passkeys: Jackson 3 needed annotations 2.22, or registration crashed with NoSuchMethodError.
15. Staff paths were routed to the dealer service, and reseeding didn't reactivate revoked seeded staff.
16. Identity's session validation wrote the sessions row on every call (the W3 bottleneck). It's now a read, with a write at most once a minute.
17. The gateway reused upstream connections Tomcat had already closed, so the first burst after a quiet spell failed with 500s.
18. Tests and tools:
    - The journey opened an older order of the seeded buyer.
    - The passkey test raced sign-out's navigation.
    - SSE parsers expected `event: x`, but Spring sends `event:x`.
    - A blocking `spawnSync` faked a 48 s delivery latency in W3.

## 7. Gaps and risks for P3

- **One hot pool doesn't scale on this stack.** Joins serialise on the pool row, and fan-out is serial per pool (one FIFO group, about 26 events/s). Options: shard or batch the count updates, cheaper per-message processing in realtime, or a per-pool coalescing delta.
- **Realtime crashes instead of shedding load.** At about 1,850 streams per 360 MB instance it ran out of heap. It needs an admission limit (refuse with 503 before running out) and a measured heap size.
- **Post-storm delivery p95 is 21 s.** Likely cause: messages held by the killed instance stay invisible until the SQS visibility timeout. Shorten it, or use a visibility heartbeat.
- **Spring Cloud Gateway returns 500 (not 503)** when an upstream connection drops.
- **A freshly restarted gateway times out under a burst.** It's a cold JVM; consider a warm-up step or readiness gating.
- **The MF-2 standard-topic test is a known flake in CI.** The duplicate has twice taken more than 20 s to arrive via LocalStack; the wait was raised to 60 s. The root cause is unproven.
- **Real-account refunds are untested.** The account has refunds disabled.
- **Hindi strings are machine-drafted and unreviewed.** 78 strings are still hard-coded in English (`reports/p2/i18n-untranslated.md`).
- **Five routes exceed the starting JS caps** (Q-037). The budgets hold them at baseline + 10%.
- **The 360 px price breakdown scrolls horizontally.** It's an allowed scroll container, but the source column is hidden until you scroll.
- **Not built:** dealer stream E121 and buyer stream E122 (501), staff invites E010/E011, and event-driven cache eviction on StaffRevoked.

## 8. Your actions needed

1. **Review and merge PR #3.**
2. **Decide the open questions:**
   - Q-045: may a buyer rejoin a pool after leaving?
   - Q-043: passkeys required for dealer staff? Attestation?
   - Q-044: should revocation also end the staff member's sessions?
3. **Enable refunds on the Razorpay test account** (or confirm they stay off), then rerun the refund half of Step 0.1.
4. **Get a native-speaker review of `web/messages/hi.json`,** especially deposit, refund and cancellation wording.
5. **Your `.env` changed:** I appended generated `CLIENT_SECRET_REALTIME` and `DB_PASSWORD_REALTIME` (missing keys only; nothing else changed), and created the `realtime` role and schema in your already-running local Postgres. A fresh database gets them from `deploy/local/postgres/01-schemas.sh` automatically.
6. **For a real domain,** set `PASSKEY_RP_ID` and `PASSKEY_ORIGINS` (defaults are `localhost` and `http://localhost:3000`).
7. **Optional:** rerun W5 at 2,000 streams on a machine with more free memory, or after raising realtime's memory cap.

Phase 3 not started.
