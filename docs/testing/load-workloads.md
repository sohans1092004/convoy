# Load-test workloads W1–W9 (PLAN §8) — specified in P0, run in P7 on the **full** profile

Tool: k6 (HTTP + SSE via xk6-sse) from load-generator EC2 instances in the same region (ap-south-1), separate from the system under test. Timestamps from the gateway access log and service metrics (ADOT → CloudWatch), not from k6 alone. Emails go to a mock sink (PLAN §6). Payments use Razorpay test mode only for W6 replays (recorded fixtures signed with the test webhook secret — no live Razorpay calls under load).

## Common stage profile
| Stage | Purpose | Default duration |
|---|---|---|
| Warm-up | JIT, caches, connection pools | 5 min at 20 % of steady rate |
| Steady | measure targets | 20 min at target rate |
| Saturation | find the knee: +20 % every 3 min until p99 > 2 s or error rate > 1 % | ≤ 21 min |
| Recovery | drop to steady rate; time until p95 back within target | 10 min |

Dataset baseline (seeded by a deterministic generator, seed recorded): 50,000 buyers, 500 dealerships / 2,000 staff, 30 models / ~150 variants, 5,000 pools across states (FORMING 30 %, QUOTE_OPEN 20 %, FINAL_ROUND 10 %, DECISION_OPEN 10 %, CONFIRMATION 5 %, BOOKING 15 %, terminal 10 %), ~100,000 quote line items.

## Workloads
| ID | Request mix | Dataset specifics | Arrival pattern | Metrics | Pass / fail |
|---|---|---|---|---|---|
| **W1** Catalogue reads | 60 % model list, 30 % model detail, 10 % price estimate; 80 % cache-hit keys / 20 % cold keys (rotating cities) | baseline | open model, Poisson, 400 req/s steady | p50/p95/p99 by route, CloudFront hit ratio, origin req/s, catalog CPU | p95 < 300 ms, p99 < 800 ms at gateway; error < 0.1 % |
| **W2** Authenticated pool reads + membership | 70 % pool snapshot, 15 % join-interested, 10 % commit (payment stubbed at Razorpay boundary), 5 % leave; 10,000 distinct buyers over 1,000 pools | baseline | Poisson 300 req/s | latency per route, `committed_count` vs DB truth | p95 < 300 ms (pool read, join); counts exact after drain |
| **W3** Concurrent joins/leaves on **one** pool | 2,000 buyers: join+commit, 500 of them leave, 300 double-click (same Idempotency-Key twice) | single pool, min 10 | closed model: 2,000 VUs start within 10 s | lock wait time, 409 rate, final counts | **interested/committed counts exactly equal** to DB recount and expected values; one membership per buyer; zero duplicate payments |
| **W4** Quote submissions at the deadline | 1,000 submissions (50 pools × 20 dealers); 30 % revisions | 50 pools in QUOTE_OPEN with deadline T | **synchronised:** 70 % of submissions released in [T−5 s, T+1 s] by a shared k6 start time; plus 100 deliberately at T+0..+2 s | commit_ts vs deadline per quote, `p99(commit_ts − check_ts)`, p95 submit latency, 409 deadline-passed count | **0 quotes with commit_ts ≥ deadline; 0 lost** (every 2xx has a version row; every version row had a 2xx or idempotent replay); p95 submit < 800 ms in the final 5 s |
| **W5** SSE | 10,000 connections (8,000 pool streams over 500 pools, 2,000 dealer streams); fan-out of 50 events/s; at minute 20 kill 50 % of realtime tasks → reconnect storm; 2 % slow consumers (read 1 msg/10 s) | baseline | ramp 10,000 connections over 5 min; hold 30 min | open connections, delivery p95 (commit→client), reconnect time to snapshot-consistent, slow-consumer disconnects, memory per task | 10,000 held 30 min; after storm 100 % clients snapshot-consistent within 60 s; slow consumers disconnected (not buffered); delivery p95 < 2 s |
| **W6** Duplicate and delayed webhooks | replay recorded `payment.captured` / `refund.processed` webhooks: each delivered 1–5×, 10 % delayed 1–30 min, 5 % out of order (refund.failed after processed), 1 % bad signature | 5,000 deposits | Poisson 50 webhooks/s with bursts of 500 in 10 s | effects per payment, ledger invariants, webhook p95 | exactly one capture/refund effect each; ledger balanced; bad signatures 400 with no effect |
| **W7** Order saga under downstream failure | 500 pools resolve at the same confirmation deadline (~5,000 orders) | CONFIRMATION pools | Toxiproxy: payment latency +2 s for 5 min, quote cut for 3 min, payment 50 % resets for 2 min | saga step completion time, retries, compensations, stuck steps | every order reaches BOOKED/VOIDED/DECLINED; every saga step DONE or compensated within 30 min after faults clear; no deposit both applied and refunded |
| **W8** Notification backlog | 100,000 notifications enqueued in 30 min (avg 55.6/s, bursts 500/s) | mock SES sink | 30 min burst | oldest-message age, delivery latency p95, retries, DLQ growth, drain time | queue drains within 15 min after burst end; DLQ growth = injected failures only; no upstream latency impact (W2 p95 unchanged ±10 %) |
| **W9** Mixed peak (headline numbers) | 40 % W1, 30 % W2 reads, 10 % joins/commits, 10 % comparison reads, 5 % quote submits, 5 % order reads; 10,000 concurrent users (1 req/10 s each) + 10,000 SSE | baseline | 1,000 req/s steady; one synchronised deadline event (50 pools) at minute 15 | **p95/p99 on named routes**: pool read, join, quote submit, comparison | p95 < 300 ms, p99 < 800 ms on each named route during steady state; misses reported, not hidden |

## Run metadata template (one file per run, `reports/load/<date>-<W>.md`)
```yaml
workload: W4
run_id: 2027-02-10T10-00Z-W4-a
commit: <git sha of every service image + k6 scripts>
profile: full
region: ap-south-1
environment:
  ecs: {service: quote, tasks: 2-8, cpu: 1024, memory: 2048, autoscaling: "CPU 60%"}
  rds: {class: db.m7g.large, multi_az: true, storage_gb: 50}
  elasticache: {node: cache.t4g.small, replicas: 1}
  limits: {db_pool_per_task: 20, jvm_heap: "1200m", sqs_visibility_s: 30}
dataset: {seed: 42, buyers: 50000, pools: 5000}
stages: {warmup_min: 5, steady_min: 20, saturation_min: 21, recovery_min: 10}
load_generators: {count: 2, type: c7g.xlarge}
results: {p50: , p95: , p99: , error_rate: , throughput: , saturation_knee: , recovery_s: }
pass: true|false
misses_and_notes: ""
cost: {start: , end: , usd: }
```
