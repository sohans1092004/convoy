# Convoy — evidence inventory

Every number we might quote, with where it was measured. **Where** is one of: **laptop** (one Windows laptop, Docker
Desktop on WSL 2), **CI** (GitHub Actions), **live lite host** (the public demo, https://convoy-main.duckdns.org, one
AWS t4g.medium, 2 vCPU / 4 GiB; runs **2278af4** since 2026-11-01 20:19 UTC, shown at `/version`), **simulated** (a fake or synthetic input stands in for the real thing), **estimate**
(arithmetic, not a bill). **Commit** is the commit measured when the source names one, otherwise the commit that
recorded the result. A number not in this file is not evidence. Missed and unmeasured targets: §8.

## 1. Correctness

| Claim | Value | Where | Date | Commit | Source |
|---|---|---|---|---|---|
| Price engine vectors, Java | 84/84 pass (54 pricing + 30 tier, incl. snapshot SHA-256) | laptop + CI | 2026-10-24 | 405d1ba | reports/P1_REPORT.md §4 |
| Price engine vectors, TypeScript | 86/86 pass (84 vectors + 2 count checks); byte-for-byte the same as Java | laptop + CI | 2026-10-24 | 405d1ba | reports/P1_REPORT.md §4 |
| Tier vectors through the order service | 30/30 (`TierVectorsIT`; tier prices synthetic, mapped to engine discounts) | laptop + CI | 2026-10-26 | a41fe5d | reports/P4_REPORT.md §4 |
| Deposit outcomes through the payment service | 27/27 (`PaymentIT.depositOutcomeVector`: states, ledger entries, reason codes) | laptop + CI | 2026-10-26 | a41fe5d | reports/P4_REPORT.md §4 |
| Saga crash recovery | 4 boundaries × crash before/after commit = 8 runs, same business result as no crash. Crashes are thrown in-process, no process is killed | laptop + CI | 2026-10-26 | a41fe5d | reports/P4_REPORT.md §3–4 |
| Messaging failure tests MF-1..MF-6 | 6/6 + MF-2 FIFO pass (MF-2 standard topic: known CI flake, wait raised to 60 s, root cause unproven) | laptop (LocalStack) + CI | 2026-10-25 | e5f62d4 | reports/P2_REPORT.md §4, reports/p2/messaging-failure-results.md |
| MF-6 drain | 5,000 events (test) / 50,000 events applied exactly once; 50,000: publish 67.6 s, drain 118.5 s, ≈ 422/s | laptop (LocalStack) | 2026-10-25 | e5f62d4 | reports/P2_REPORT.md §4 |
| Authorisation tests | 42 P1 endpoints carry 106 authz test ids; deny half run for every P1 endpoint; P3 matrix covers every built endpoint | laptop + CI | 2026-10-24 / 2026-10-25 | 405d1ba / 9bb0b8d | reports/P1_REPORT.md §4, reports/P3_REPORT.md §4 |
| Authz bugs found by the matrix | validation before authorisation on internal endpoints (400 leaked shape); E031 400 and E042 501 to wrong roles | laptop | 2026-10-24 / 2026-10-25 | 405d1ba / e5f62d4 | reports/P1_REPORT.md §6 items 4–5, reports/P2_REPORT.md §6 item 13 |
| Vacuous checks found and fixed | 6 (each proved by breaking it, except #6 az-t6 probe: no proof recorded) | laptop | 2026-10-26 | 66be869 | reports/P5_REPORT.md §4 (P5.X) |
| Vacuous Storybook page-state checks found and fixed | all 24 stories rendered their error state (the API mock never reached the client) yet passed; fixed, 52/52 story tests pass on the real states and the new non-member test fails when its fix is reverted | laptop | 2026-11-02 | 6a49479 | reports/p8/PROGRESS.md (P8.C) |

## 2. Concurrency

| Claim | Value | Where | Date | Commit | Source |
|---|---|---|---|---|---|
| Parallel joins exact | 100 parallel joins → interested_count 100, 100 distinct aggregate versions; 20 requests with one key → 1 membership + 19 replays; 100 commits + 100 captures → committed_count 100, exactly one PoolReady | laptop + CI | 2026-10-24 | 405d1ba | reports/P1_REPORT.md §4 |
| One open pool per key | 50 concurrent creates → 1 pool | laptop + CI | 2026-10-25 | e5f62d4 | reports/P2_REPORT.md §2 item 5 |
| Submissions around the deadline | 50 parallel submissions: every early one kept, every late one `deadline-passed`, nothing stored | laptop + CI | 2026-10-25 | 9bb0b8d | reports/P3_REPORT.md §4 |
| W3 counts under load | 1,000 VUs, 4,000 buyers, one pool, 5 min: 4 counts equal (1,372 each). **But 27.6 % of requests refused/failed, join p95 41.0 s** | laptop (k6 in Docker) | 2026-10-25 | 4788f54 + fixes in 6de3f67 | reports/P2_REPORT.md §5, reports/p2/w3.json |

## 3. Realtime

| Claim | Value | Where | Date | Commit | Source |
|---|---|---|---|---|---|
| W5 streams held | 1,000 streams, 2 realtime instances, 20 pools, 2 % slow consumers, 5 min (**not** the 10,000 target) | laptop | 2026-10-25 | 6de3f67 | reports/P2_REPORT.md §5, reports/p2/w5.json |
| W5 delivery, commit → client | p50 143 ms / p95 238 ms / p99 2,565 ms (36,822 samples) | laptop | 2026-10-25 | 6de3f67 | reports/p2/w5.json |
| W5 storm recovery | 486 of 486 cut streams recovered with a fresh snapshot; recovery p95 6.8 s, max 14.3 s; delivery p95 after the storm 21.3 s | laptop | 2026-10-25 | 6de3f67 | reports/P2_REPORT.md §5 |
| W5 slow consumers cut | 14 of 20 | laptop | 2026-10-25 | 6de3f67 | reports/P2_REPORT.md §5 |
| Memory per 1,000 streams | ≈ 180 MiB realtime, 12 MiB gateway (from a 200-stream run; the 1,000-stream run sat at the 360 MB cap) | laptop | 2026-10-25 | 6de3f67 | reports/P2_REPORT.md §5 |
| 2,000-stream attempt | 1,859 opened, stopped for host memory; realtime-2 OutOfMemoryError at ≈ 1,850 streams (stream cap 1,000/instance added in P2.5) | laptop | 2026-10-25 | 6de3f67 | reports/P2_REPORT.md §5, reports/p2.5/PROGRESS.md |
| Reconnect (two-instance scenarios) | after graceful stop 7.4 s, after kill 4.4 s; fan-out p95 38–236 ms | laptop + CI | 2026-10-25 | e5f62d4 | reports/P2_REPORT.md §5 |
| Revocation (AZ-T6, after AMENDMENT-008) | 3 runs: API first refusal 1.0 / 2.0 / 2.0 s, 0 successes after; stream closed 0.6 / 1.4 / 0.9 s (API polled every 1 s) | laptop | 2026-10-25 | e5f62d4 | reports/p2.5/revocation.json |

## 4. Live lite host (VM numbers, not laptop numbers)

| Claim | Value | Where | Date | Commit | Source |
|---|---|---|---|---|---|
| Demo journey on the public URL | PASS once, 4.5 min (real TLS, mobile-360; dealers simulated, payment simulated) | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Demo journey on the public URL, after the P8 redeploy | PASS once, 4.5 min: scenario 13.3 s, committed 20.8 s, 3 simulated offers 135.5 s, booked + voucher 263.1 s, dealer and admin views | live lite host | 2026-11-01 | 2278af4 | reports/p8/PROGRESS.md (P8.C) |
| Deploy (`deploy.sh` via `scripts/deploy-lite.sh`: backup, one service at a time, smoke test) | 268 s, no rollback | live lite host | 2026-11-01 | 2278af4 | reports/p8/PROGRESS.md (P8.C) |
| Reboot recovery | catalog API back after 120 s; all 17 containers healthy after 157 s | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Backup + restore drill | backup 68,639 bytes (age-encrypted, OCI bucket); restored 125 tables / 616 rows, no table with fewer rows than live; 10 restore errors, all role-grant `permission denied`, no data | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Memory, idle after the journey | host 3,031 of 3,824 MiB used, 792 MiB available (+2 GiB swap); containers 2,432 MiB | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| TLS | Let's Encrypt, valid to 2027-01-30; http → https 308 | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Port scan from outside | 25 TCP ports: open 22, 80, 443 only; 22 admin IP only; `/metrics`, `/actuator/*`, internals 404 | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Lite stack cold start / idle | 56 s to all healthy; idle 2.3 GiB | laptop | 2026-10-31 | — (P6.A) | reports/p6/memory-laptop.md, reports/p6/lite-measure-20261031T114124Z/ |
| Laptop prod rehearsal journey | PASS through Caddy in 4.1 min | laptop (real AWS shared resources) | 2026-11-01 | — | reports/P6_REPORT.md §5 |
| `scripts/up.sh` (one-command local start) | exit 0 in 225 s (second run, Gradle and Docker caches warm; the first stopped on the shared-volume password mismatch); stack ready 54 s after compose up | laptop | 2026-11-02 | 6a49479 | reports/p8/PROGRESS.md (P8.C) |
| Full-lifecycle journey at 360 px with the per-screen layout check (no horizontal scroll, prices ≥ 12 px and unclipped) | PASS once, 5.0 min | laptop | 2026-11-02 | 6a49479 | reports/p8/PROGRESS.md (P8.C) |

## 5. Frontend

**First-load JS per route** (gzip level 9, production build, `web/scripts/js-first-load.mjs`). Laptop build at 5d79ff3;
deployed since 2026-11-01 as part of 2278af4.

| Route | KB before (18bd288) | KB now (5d79ff3) | Cap | Met |
|---|---|---|---|---|
| discover | 185.3 | 164.0 | 170 | yes |
| model | 189.1 | 167.7 | 170 | yes |
| pool | 189.5 | 168.1 | 170 | yes |
| compare | 186.5 | 167.3 | 190 | yes |
| orders | 179.9 | 160.7 | 170 | yes |
| order | 197.6 | 178.4 | 170 | **no (+8.4)** |
| saved | 184.2 | 165.0 | 170 | yes |
| sign-in | 183.6 | 164.4 | 170 | yes |
| dealer | 183.7 | 164.5 | none | n/a |

Source: reports/p7/frontend.md (2026-11-01).

**Lighthouse, live site** (mobile preset, simulated slow 4G, median of 3 runs, @lhci/cli 0.15.1; pool page through a
fresh "Try the demo" session). Lab gate (docs/testing/frontend-quality.md §2): LCP ≤ 2.5 s, TBT ≤ 200 ms, CLS ≤ 0.1,
performance ≥ 85.

Build **2278af4** (after the P8 redeploy), 2026-11-01 20:51 UTC, 32 min after the deploy, laptop idle. Source:
reports/p8/lighthouse-live.json.

| Page | LCP | CLS | TBT | Performance | Gate |
|---|---|---|---|---|---|
| discover | 1,090 ms | 0 | 111 ms | 98 | met |
| model | 1,162 ms | 0 | 85 ms | 97 | met |
| pool | 1,822 ms | 0.049 | 179 ms | 94 | met |

**The same build missed the TBT and performance gates on every page in two earlier runs**, 13–17 min after the deploy and
right after the live demo journey: TBT 630 / 479 / 534 ms, performance 81 / 87 / 80 (20:28 UTC, local stack running,
reports/p8/lighthouse-live-2278af4-busy-laptop.json) and TBT 577 / 603 / 535 ms, performance 80 / 83 / 81 (20:32 UTC,
laptop idle, reports/p8/lighthouse-live-2278af4-2032.json). The cause of those runs is not established. A back-to-back
A/B of both builds on one laptop (reports/p8/lighthouse-ab-local.md) rules out a code regression: median TBT 03292c4 →
2278af4, discover 190 → 128 ms, model 297 → 84 ms. Live TBT therefore varies run to run by more than the gate's margin;
three runs over 25 minutes are not enough to call the gate reliably met.

Build **03292c4** (before the redeploy), 2026-11-01 19:00 UTC: LCP 1,446 / 1,133 / 2,065 ms, CLS 0, TBT 194 / 218 /
236 ms, performance 93 / 93 / 91 (reports/p8/lighthouse-live.json at commit 1ba7572). **Not a clean before/after pair:**
the cause of the TBT increase is not established (the smaller bundles of 2278af4 would predict the opposite; TBT under
simulated throttling also depends on the measuring laptop's CPU).

Earlier, laptop (fda7cd2, 2026-10-25, reports/p2/lighthouse.json): LCP 2,789 / 2,607 / 2,745 ms (all over 2.5 s),
TBT 92 / 136 / 129 ms. Laptop and live numbers are different setups and are not a before/after pair.

## 6. Delivery

| Claim | Value | Where | Date | Commit | Source |
|---|---|---|---|---|---|
| Production-config bugs found by AWS smoke runs | smoke2: db-init `SET ROLE` on non-superuser RDS; `full.sh` helper `exit` skipped the abort. smoke3: Flyway without password on direct RDS URL; identity's own URL `localhost`. smoke7–9: Service Connect names unresolvable (**not fixed**, full profile parked) | AWS full profile | 2026-10-31 – 11-01 | per run in source | reports/P6_REPORT.md §5 |
| Found by the laptop prod rehearsal (before the VM) | blank `AWS_ENDPOINT` → empty SNS/SQS override; local-only `VAPID_SUBJECT`; no ALB route to the payment sim; missing `sns:GetSubscriptionAttributes`; Caddy gzip ETag → `If-Match: "NaN"` → 428 | laptop | 2026-11-01 | — | reports/P6_REPORT.md §5 |
| Found while deploying the lite host | iptables step assumed Oracle's REJECT rule; smoke check before Caddy's first certificate; placeholder `AGE_RECIPIENT` | live lite host | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §4 |
| Reaper canaries | canary1 FAIL (provider lock hashes, pre-destroy refresh reading the secret, `SetWebACL` denied twice, stale state lock), canary2 FAIL (`ecs:DeregisterTaskDefinition` denied); last fix applied, **unproven**; reaper schedule now denied by an org SCP | AWS | 2026-10-31 | d226b83 | reports/P6_REPORT.md §5 |
| AWS bill to date | gross $3.18 for the account (shared with Concord), ≈ $2.33 of it Convoy; all covered by credits, **$0 billed** | AWS Billing, read by the owner | 2026-11-01 | — | owner's Billing figures (reported in the P8 session, not re-queried) |
| AWS spend, full-profile runs (per-run arithmetic) | smoke1–smoke6 + canaries ≈ $2.75 (**estimate**; smoke7–9 not estimated) | estimate | 2026-11-01 | — | reports/P6_REPORT.md §5 |
| AWS always-on costs | `shared` ≈ $4.66/month; interim lite EC2 ≈ $37.50/month (**estimates**) | estimate | 2026-11-01 | — | reports/P6_REPORT.md §1, §4 |
| Full profile | **PARKED** after smoke9: applies end to end (107 resources), all 16 services stable once, journey never passed; every run TEARDOWN VERIFIED | AWS | 2026-11-01 | 03292c4 | reports/P6_REPORT.md §5 |
| Real Razorpay test mode | capture passed against api.razorpay.com (`payment.captured` 0.70 s after authorized); refund blocked (refunds disabled on the account) | Razorpay test mode | 2026-10-25 | e5f62d4 | reports/P2_REPORT.md §2 item 1, reports/p2/razorpay-real-run.md |

## 7. What the labels mean for quoting

- Laptop numbers say the logic is right and roughly how it behaves on one machine; they are not capacity claims.
- The live lite host is one small VM serving a demo with simulated dealers and a simulated payment; it was never
  load-tested.
- The demo journey on the live host passed once; a repeat pass rate is not measured.

## 8. Missed or unmeasured targets

| Target (PLAN.md §3, docs/testing) | Status |
|---|---|
| W5: 10,000 SSE connections on AWS, 30 min, reconnect storm of 10,000 | **Not measured.** Largest held: 1,000 streams on the laptop (§3). W5 AWS script built (tools/load/w5_aws.mjs), never run: the full profile is parked. |
| ~1,000 req/s at the edge (W1/W2/W9 on the full profile) | **Not measured.** No load test has touched AWS. |
| HA drills (kill each service, RDS failover, Redis flush, SQS backlog on full) | **Not run.** Fargate quota 8 vCPU; full profile parked. Laptop-only equivalents: realtime kill (W5), Redis FLUSHALL, poison message (MF-5), consumer offline (MF-6). |
| W3 capacity on one pool | **Missed** on the laptop: 27.6 % refused/failed, join p95 41.0 s (§2). |
| W4 (50 pools × 20 dealers) | **Not built** (needs load-test dealerships). |
| Lighthouse lab gate TBT ≤ 200 ms, live | **Not reliably met:** on 2278af4 met in the 20:51 run (111 / 85 / 179 ms), missed on every page in the 20:28 and 20:32 runs (479–630 ms), cause not established; on 03292c4 model 218 ms and pool 236 ms missed (§5). |
| Lighthouse lab performance ≥ 85, live | **Not reliably met:** 98 / 97 / 94 at 20:51, 80–87 in the two earlier runs on the same build (§5). |
| Lighthouse lab gate LCP ≤ 2.5 s | Met on the live site in every run (1.1–2.2 s on 2278af4); **missed** on the laptop run of 2026-10-25 (2.6–2.8 s). |
| First-load JS cap 170 KB, order page | **Missed:** 178.4 KB (+8.4). |
| RUM p75 LCP / INP / CLS from real users | **Not measured** (no real-user traffic collected). |
| Reaper end-to-end destroy | **Unproven:** last canary failed; fix applied without a re-run. |
| Real Razorpay refund | **Not run** (refunds disabled on the test account). |
| AWS bill after 2026-11-01 | **Not measured** here; the Nov 1 figures are in §6 (owner checks the Billing page). |
