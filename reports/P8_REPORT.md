# P8 report: release

> **Status (2026-11-02): P8 done.** Live at **https://convoy-main.duckdns.org**, build **2278af4** (`/version`), deployed
> 2026-11-01 20:19 UTC. Every number below is from [docs/EVIDENCE.md](../docs/EVIDENCE.md), with its label. Progress
> log: [p8/PROGRESS.md](p8/PROGRESS.md).

## 1. What shipped
| Subtask | Result | PR |
|---|---|---|
| P8.0 Repository safety | Full-history secret scan (gitleaks, every branch): 2 findings, both test fixtures, no real secret. `.gitignore` covers keys, env files, Terraform state/plans, `.run/`. MIT licence. Literal demo secret removed from `measure.env`. | #10 |
| P8.A Evidence inventory | [docs/EVIDENCE.md](../docs/EVIDENCE.md): every quotable number with where, when, commit and source; missed targets listed openly. | #10 |
| P8.B README and architecture | README with live demo, screenshots, diagram, decisions → ADRs, highlights → EVIDENCE, one-command start; [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md). | #10 |
| P8.C Polish and redeploy | Non-member "Reconnecting…" loop fixed; 24 vacuous Storybook state stories fixed; `/version` + footer; "deposit returned" wording; 360 px layout check in the journey; `scripts/up.sh`; laptop-only deploys; lite-ec2 can no longer replace the live host; live redeploy. | #10, #11 |
| P8.D Demo video | [docs/demo-script.md](../docs/demo-script.md); screen recording of the live site by `web/scripts/record-demo.mjs`: 3 min 30 s, 1280×720, captions, waits sped up 8× and labelled. The .mp4 is kept out of git (owner's machine). The architecture diagram is small on screen. | this PR |
| P8.E How it was built | [docs/how-it-was-built.md](../docs/how-it-was-built.md), [docs/interview-guide.md](../docs/interview-guide.md), [docs/postmortems/INDEX.md](../docs/postmortems/INDEX.md). | this PR |
| P8.F Résumé material | [p8/resume-bullets.md](p8/resume-bullets.md). | this PR |

## 2. Evidence summary (labels as in EVIDENCE.md)
- **Correctness (laptop + CI):** 84/84 price vectors in Java and TypeScript; 30/30 tier vectors; 27/27 deposit outcomes;
  saga crash recovery at 4 boundaries (8 runs); MF-1..MF-6 pass; 106 authz test ids on 42 endpoints; 30 vacuous checks
  found and fixed (6 in P5, 24 in P8).
- **Concurrency (laptop + CI):** 100 parallel joins exact; 50 concurrent creates → 1 pool; 50 submissions around a
  deadline, every late one refused.
- **Realtime (laptop):** 1,000 streams; 486/486 recovered after an instance kill; revocation closed streams in 0.6–1.4 s.
- **Live lite host:** demo journey PASS in 4.5 min (2278af4); redeploy 268 s, no rollback; reboot to healthy 157 s
  (03292c4); backup restored (125 tables); only ports 22/80/443 open; Lighthouse at 20:51 UTC TBT 111 / 85 / 179 ms,
  performance 98 / 97 / 94, LCP 1.1–1.8 s.
- **Cost:** $0 billed as of 2026-11-01 (≈ $2.33 Convoy, covered by credits; owner's Billing figures).

## 3. Known misses (not rounded into passes)
- **10,000 SSE, ~1,000 req/s and HA drills on AWS: not measured** (the full profile is parked; capacity numbers are
  laptop numbers).
- **W3 capacity on one pool (laptop):** 27.6 % refused or failed, join p95 41.0 s.
- **Live Lighthouse TBT and performance: not reliably met.** Met at 20:51 UTC, missed on every page in two earlier
  runs on the same build (cause not established; an A/B rules out a code regression).
- **Order page first-load JS:** 178.4 KB against a 170 KB cap.
- **Reaper end-to-end destroy unproven; real Razorpay refund not run; RUM not collected.**
- CI does not validate `infra/aws/lite-ec2`.

## 4. Left for later
- **Full profile:** fix service discovery (ECS Service Connect, or a Cloud Map private DNS namespace), then the AWS load
  tests and HA drills ([P6_REPORT.md §5](P6_REPORT.md)).
- **Oracle move:** the retry task keeps checking A1 capacity every 2 minutes; move the demo when it appears and retire
  the interim EC2 host.
- **Hindi review** by a native speaker (machine-drafted today).
- **Audit-log pruning** (the hash-chained log only grows).
- CI validation for `infra/aws/lite-ec2`; the order page's bundle.
