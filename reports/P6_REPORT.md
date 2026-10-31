# P6 report: deployment

> **Status: built and validated; the cloud parts wait for your applies.** Sections 4, 5 and 7 and the live half of 6 are
> filled in after `infra/aws/shared` and `infra/oci` are applied and the full-profile smoke run is approved. Nothing
> that costs money has been created.

## 1. Summary
- **Live demo URL:** pending (`https://convoy-demo.duckdns.org` once the VM, DuckDNS name and first deploy exist).
- Convoy now has a production configuration (`prod` profile validated at startup, topology checked not created),
  multi-arch images by digest, a public demo mode safe for strangers, Terraform for the AWS resources the demo uses,
  for the Oracle Always Free VM, and for the on-demand full AWS stack, plus the host scripts and workflows that deploy,
  back up and tear down. Laptop measurement: **cold start 56 s, idle 2.3 GiB** for the whole lite stack.
- Expected AWS bill for the always-on demo: **≈ $7.26/month** (≈ $3.96 with DLQ-only alarms); the VM is $0.

## 2. Built (A–E)
**P6.A production configuration and images**
- `prod` profile: `ProdGuard` checks every key of the service's own config before any bean starts and lists every problem
  at once: unresolved env, blank secrets, LocalStack endpoint, missing account id, `localhost`/`mailpit`/`convoy.test`
  values, seed users, SMTP without auth + STARTTLS, malformed URLs.
- Messaging topology is infrastructure: under `prod` services check topics, queues, DLQs, redrive and subscriptions
  (including every consumed event type in the filter) and refuse to start, naming what is missing. P1 deviation 4 closed.
- `images` workflow: amd64 + arm64 on native runners, pushed by digest to GHCR, multi-arch index per image, `images.env`.
  First run green (5 min 30 s).
- `deploy/lite/compose.yml`: Caddy, Postgres, Redis, 1 realtime, memory limits everywhere, small-heap JVMs without the
  OTel agent, the simulated payment, demo bots. PgBouncer not needed: 10 × 4 connections under `max_connections` 100.
- Messaging cost knobs: pollers per queue, long-poll wait, DLQ check interval (lite: 1, 20 s, 15 min).

**P6.B public demo mode** (see PROGRESS.md for the detail)
- Try the demo: isolated 24 h scenario (temporary buyer + admin of the visitor's own demo dealership), a fresh pool,
  simulated dealers quoting on demo durations, dealer view switch, read-only admin view with synthetic figures.
- Isolation: demo pools belong to their owner; a demo dealership is invited only to its owner's pools.
- Limits: 20 per IP and 200 in total per day (503 "The demo is full for today"); expiry through the P5.D erasure flow.
- Banner on every page; payments through the built-in simulated payment; email sign-in behind `EMAIL_SIGN_IN`.

**P6.C AWS resources for lite** (`infra/aws/shared`, validated, not applied)
- SNS/SQS generated from the services' own layout (`scripts/dump-topology.py`): 7 topics, 33 queues + 33 DLQs
  (4 FIFO), raw delivery, filter policies, maxReceiveCount 5, SSE.
- Alarms: DLQ not empty and oldest message > 15 min, grouped 10 queues per alarm, emailed.
- S3: exports (24 h lifecycle) and evidence (browser CORS for the site only); private, SSE-S3, public access blocked, TLS only.
- SES: domain identity + DKIM only with a domain; otherwise skipped and email sign-in stays off.
- IAM: host user with exactly the calls the services make on exactly these resources; a separate SMTP user that can only
  send from `mail_from`. Rotation: `infra/aws/shared/README.md`.
- Budgets: $5 and $20 actual, $20 forecast. Cost arithmetic: `docs/cost/aws-cost-estimate.md §6`.

**P6.D Oracle VM and live demo** (`infra/oci`, scripts; validated, not applied)
- VCN, public subnet, security list (SSH from `admin_cidr` only; 80/443), A1.Flex 1 OCPU / 6 GB, Ubuntu 24.04 aarch64,
  50 GB boot, SSH keys only, backup bucket with 7-day lifecycle and a pre-authenticated request; `retry-apply.sh` for
  "Out of host capacity" (same region and shape).
- `bootstrap.sh` (idempotent): unattended upgrades, fail2ban, SSH hardening, iptables 80/443, 2 GB swap, Docker with
  log rotation, restricted `deploy` user (forced command), cron for backups and DuckDNS.
- `deploy.sh <sha>`: backup, checkout, pull by digest, one service at a time until healthy, smoke test, automatic
  rollback; `deploy-lite` workflow over SSH with `images.env` on stdin.
- Backups: nightly `pg_dumpall` | gzip | `age` to OCI Object Storage, 7 kept; `restore-check.sh` restores into a
  scratch database and diffs every table's row count.
- `e2e/demo.spec.ts`: the public demo journey against any URL.

**P6.E full profile** (`infra/aws/full`; validated, not applied)
- 2-AZ VPC (one NAT by default), CloudFront + WAF (managed common rules, per-IP rate) -> ALB (CloudFront prefix list and
  a secret header) -> web; ECS Fargate ARM, Service Connect, 2 tasks per service; autoscaling: realtime on SSE
  connections (EMF), notification on its queue backlog, others on CPU; RDS PostgreSQL single-AZ with a `multi_az` flag;
  PgBouncer (transaction pooling, migrations go direct); ElastiCache; ADOT -> X-Ray at 5 %; one Secrets Manager secret.
- `make full-up HOURS=1` / `make full-down` through the `full` workflow: DB init before services, smoke run, a guard job
  that destroys at the maximum duration, teardown verification by tag inventory, cost report.

## 3. Deviations
- **Lite host is an Oracle Cloud Always Free VM, not EC2 behind CloudFront** (PLAN §4.7). Caddy terminates TLS with
  Let's Encrypt; SSH from one IP. deployment-profiles.md updated.
- **The full-profile smoke run uses the public demo journey** (`demo.spec.ts`), not `journey.spec.ts`: the latter needs
  the local compose stack (seed users signing in through Mailpit, `docker compose exec` checks). Both cover sign-in to
  booking with one buyer; the demo journey's quotes come from the simulated dealers.
- `journey.spec.ts` gained `REMOTE=1`, which skips its Docker-only checks (database, metrics, logs, verify-chain) for a
  deployed URL; on the host those run separately.
- **Demo endpoints are hidden from OpenAPI** (E170–E172 exist only with `DEMO_SCENARIOS=true`); the committed specs are
  the same everywhere. E055 gained an optional `owner` parameter (regenerated web client).
- **Dealer migration is V6, not V5**: V2 and V5 existed before P4.0 moved the demo seed out of Flyway.
- **Alarms cost more than grouping suggests**: CloudWatch bills every metric an alarm reads (66 alarm metrics,
  ≈ $5.60/month). `queue_age_alarms = false` keeps only DLQ alarms (≈ $2.30).
- Notification email links now use `WEB_URL` instead of a hard-coded `https://convoy.test`.
- `d001` (the P1 journey dealership) is seeded on lite too; with no staff user there it is invited but never quotes.

## 4. Memory and CPU on the VM
Pending (VM). Laptop numbers, reported separately (`reports/p6/memory-laptop.md`): cold start 56 s; idle 2,377 MiB
for all lite containers, JVMs 175–234 MiB each against 384 MiB limits; journey peak not measured on the laptop (too
little free memory), to be measured on the VM.

## 5. The full-profile smoke run, with its cost
Pending your approval of a run (`make full-up HOURS=1`, ≈ $1.49 × 1 h idle estimate, at most 1 hour).

## 6. Security checks
Built and tested so far:
- `prod` refuses local shortcuts (ProdGuardTest); missing topology stops a service (TopologyCheckIT).
- Demo: bot sessions only for simulated dealers, only with scope `demo:sessions` (DemoIT); per-IP and global caps.
- Demo dealerships never invited to another visitor's pools (DealerIT); demo pools never found or suggested to others (PoolIT).
- Caddy exposes only the web app and two payment-stub paths; deploy key limited to one command and digest-pinned images.
- IAM least privilege (host and SMTP users); buckets private, encrypted, TLS only.
Pending (VM): open ports from outside, `/metrics` / Prometheus / admin internals unreachable, TLS chain, reboot to healthy.

## 7. Backups and the restore result
Pending (VM): first nightly backup and one restore drill (`restore-check.sh`, row counts of every table).

## 8. Gaps for P7
- No load test has touched the full stack; autoscaling thresholds (2,000 SSE per task, backlog 200) are first guesses.
- The WAF per-IP rate rule would block load generators at the edge; P7 drives load at the ALB origin (cost estimate §2).
- VPC interface endpoints are not built (NAT carries SQS/SNS traffic); add them if NAT data dominates a run's cost.
- `topology.json` is regenerated by hand; a CI check against a fresh dump would catch drift before deploy.
- PowerUser on the GitHub `full` role is broad (ponytail note in `github.tf`).

## 9. Needs from me
See the PR description and the session summary: Oracle API key and limits, DuckDNS name, AWS account + `shared`
apply, `oci` apply, GitHub environments and secrets, GHCR package visibility, approval of one full-profile run.
