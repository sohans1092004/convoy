# P6 report: deployment

> **Status (2026-10-31 21:25 UTC): P6 closed except the OCI lite deploy.** AWS `shared` and the reaper are applied; OCI
> network and backup bucket are applied, the VM waits for Always Free A1 capacity (§4, §7 and the live half of §6 wait
> for it). The full-profile smoke run did **not** pass: smoke2 came up to the services, which all exited at startup;
> torn down, TEARDOWN VERIFIED. Every AWS run of this phase was destroyed and verified.

## 1. Summary
- **Live demo URL:** pending (`https://convoy-demo.duckdns.org` once the VM, DuckDNS name and first deploy exist).
- Convoy now has a production configuration (`prod` profile validated at startup, topology checked not created),
  multi-arch images by digest, a public demo mode safe for strangers, Terraform for the AWS resources the demo uses,
  for the Oracle Always Free VM, and for the on-demand full AWS stack, plus the host scripts and workflows that deploy,
  back up and tear down. Laptop measurement: **cold start 56 s, idle 2.3 GiB** for the whole lite stack.
- AWS runs in **ap-southeast-2 (Sydney)**, the only region the account allows, shared with Concord. Expected bill for the
  always-on demo: **≈ $4.66/month** (SQS $1.63, alarms $3.00, S3 $0.03); the VM is $0.

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
- Alarms: every DLQ not empty; oldest message > 15 min on the payment and order queues only; metric math, emailed.
- S3: exports (24 h lifecycle) and evidence (browser CORS for the site only); private, SSE-S3, public access blocked, TLS only.
- SES: domain identity + DKIM only with a domain; otherwise skipped and email sign-in stays off.
- IAM: host user with exactly the calls the services make on exactly these resources; a separate SMTP user that can only
  send from `mail_from`. Their keys are made outside Terraform (`scripts/create-host-keys.sh`, straight into the git-ignored
  env file), so the state holds no secrets. Rotation: `infra/aws/shared/README.md`.
- Budgets: $8 and $20 actual, $20 forecast, filtered on `project=convoy` (the account also runs Concord).
  Cost arithmetic: `docs/cost/aws-cost-estimate.md §6`. Permissions Terraform needs: `infra/aws/shared/PERMISSIONS.md`.

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
- 2-AZ VPC (one NAT by default), CloudFront (HTTPS only) -> ALB (CloudFront prefix list and a secret header) with a
  regional WAF (managed common rules, per-IP rate) -> web; ECS Fargate ARM, Service Connect, 2 tasks per service; autoscaling: realtime on SSE
  connections (EMF), notification on its queue backlog, others on CPU; RDS PostgreSQL single-AZ with a `multi_az` flag;
  PgBouncer (transaction pooling, migrations go direct); ElastiCache; ADOT -> X-Ray at 5 %; one Secrets Manager secret.
- `make full-up` / `make full-down` from the laptop with the `convoy-aws` session: DB init before services, smoke run,
  teardown verification by tag inventory, cost report. The scheduled destroy is pending a decision (no GitHub OIDC).

## 3. Deviations
- **AWS region ap-southeast-2, not ap-south-1**: the account (shared with Concord) allows only Sydney. Defaults, examples,
  cost estimate and runbooks changed; lite in India talks to Sydney (+0.3 to 0.6 s per event, reports/p6/latency-oracle-sydney.md).
- **No GitHub OIDC role**: an organisation SCP denies `iam:CreateOpenIDConnectProvider`. The `full` workflow is removed;
  the full profile runs from the laptop, and its scheduled destroy needs another runner (proposal: in-account CodeBuild).
- **WAF is regional on the ALB; no CloudFront**: CloudFront needs account verification this account does not have
  (smoke1, §5). The full profile serves HTTPS from the ALB with a self-signed certificate imported into ACM. The journey
  and k6 skip TLS verification only there (`FULL_SELF_SIGNED=1`); browsers and passkeys are not expected to work on the
  full profile. The public demo is the OCI host, with a real Let's Encrypt certificate.
- **Queue-age alarms on payment and order queues only** (your decision); DLQ alarms on all.
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
- **Alarm cost is per metric read**, not per alarm: 40 alarm metrics, 10 free, $3.00/month.
- Notification email links now use `WEB_URL` instead of a hard-coded `https://convoy.test`.
- `d001` (the P1 journey dealership) is seeded on lite too; with no staff user there it is invited but never quotes.

## 4. Memory and CPU on the VM
Pending (VM). Laptop numbers, reported separately (`reports/p6/memory-laptop.md`): cold start 56 s; idle 2,377 MiB
for all lite containers, JVMs 175–234 MiB each against 384 MiB limits; journey peak not measured on the laptop (too
little free memory), to be measured on the VM.

## 5. The full-profile smoke run, with its cost
**Attempt `smoke1` (2026-10-31 18:12–18:29 UTC): stopped before the journey, torn down, TEARDOWN VERIFIED.**
- 1 task per service (no HA claim), reaper scheduled for 19:12:55 (pinned 7fa2b0d), headroom 8 − 0 − 5.5 = 2.5 vCPU.
- 42 resources created (network, ALB, WAF, RDS, ElastiCache, IAM, Secrets Manager; services still at 0 tasks), then
  CloudFront was refused: `AccessDenied: Your account must be verified before you can add new CloudFront resources`.
- Found and fixed by this run: the abort path called `full-down` by a relative path (exit 127); the destroy needed
  image inputs the reaper does not have (`Invalid index`, which would also have broken the reaper's destroy of a real
  run); teardown verification counted already-deleted resources that the tagging API still lists.
- Destroy: 42 destroyed by 18:27; `reap.sh` re-run on the empty state: 0 destroyed, TEARDOWN VERIFIED 18:29. Direct
  checks: no Convoy VPC, RDS, ElastiCache, load balancer, Elastic IP, ECS cluster or schedule left.
- Cost (estimate; Cost Explorer lags up to 24 h): about $0.18, mostly partial-hour charges (NAT, ALB, ElastiCache, IPv4),
  RDS for ~10 min, no Fargate tasks ran.
- Next run needs HTTPS without CloudFront: built (ALB + self-signed ACM certificate, §3).

**Real-slice reaper canary `canary1` (the old SQS canary missed both destroy bugs).** `full.sh canary` applies
`infra/aws/full` with the real variables and `canary=true`: VPC, NAT, ALB + WAF + certificate, ECS cluster and 16
services at 0 tasks, no RDS or ElastiCache (72 resources). Nothing on the laptop destroys it; the scheduled reaper must.
- 18:43 scope check (delete convoy/full ALB allowed, convoy/lite denied), headroom 2.5 vCPU, 72 applied by 18:48.
- Each reaper run found one real defect that would also have broken the reaper's destroy of a real run:
  1. 19:04, pinned abad08c: `terraform init` failed, the provider lock file held only Windows hashes for `tls`.
     Fix: lock for linux_amd64/arm64 + windows (3debfa5).
  2. 19:12, pinned 3debfa5: the pre-destroy refresh read the run's secret value (`GetSecretValue`, which the reaper
     role deliberately lacks). Fix: `destroy -refresh=false` (87bec30).
  3. 19:19, pinned 87bec30: 38 destroyed, then the WAF could not be detached from the ALB (needs
     `elasticloadbalancing:SetWebAcl`), so the ALB, internet gateway and VPC stayed. Fix: action added, tag-scoped (d4125eb).
     I stopped this task mid-destroy, which left its state lock behind.
  4. 19:34, pinned d4125eb: the state was locked; the reaper waited 10 min, destroyed nothing, exited 2 with
     `REAPER STOPPED` (the designed behaviour). Fix: removed the stale lock after confirming its owner was the stopped
     task and nothing else ran.
  5. 19:49, pinned d4125eb: **FAILED, same step.** CloudTrail: `AccessDenied ... elasticloadbalancing:SetWebACL` on
     the convoy/full ALB, although the action is granted. Probable cause: SetWebACL does not support the
     `aws:ResourceTag` condition, so the tag-scoped grant never matches. Stopped here (speed mode: one fix attempt per new
     failure). Next fix, not applied: grant `elasticloadbalancing:SetWebACL` on `loadbalancer/app/convoy-full-*` by ARN
     instead of by tag, then re-run the canary.
- **Canary verdict: FAIL.** The reaper cannot yet destroy a full run that has the WAF attached. Cleaned up with the
  manual fallback (`full.sh down canary1` from the laptop): remaining 34 resources destroyed by 19:58 UTC; direct checks at 19:58: no canary1 VPC, ALB, NAT gateway, Elastic IP, WAF, ECS cluster or reaper schedule left; explicit re-run of `reap.sh infra/aws/full canary1`: 0 destroyed, **TEARDOWN VERIFIED 20:06:26 UTC**.
  (Its first verification looped: the tag index still listed the 16 deleted ECS services and `alive()` had no case for
  services; INACTIVE services now count as gone.)
- Fixes after canary1 (your OK): the reaper may run `elasticloadbalancing:SetWebACL`, `wafv2:DisassociateWebACL` and
  `wafv2:DeleteWebACL` on `convoy-full-*` ALBs and web ACLs by name; `enable_waf` (default false) keeps the WAF in the
  code for a dedicated check, off for the canary, the smoke run and P7 load runs (the per-IP rule blocks one k6
  generator). Commit d226b83. One canary re-run allowed, no further fixes.

**Canary `canary2` (enable_waf=false, reaper pinned d226b83):** **FAIL; stopped, no further fix (your rule).**
- 20:06 applied 70 resources (no WAF); reaper moved forward to 20:15:45 at your request; scope check and 2.5 vCPU headroom ok.
- The reaper (pinned d226b83) destroyed 38, then stalled: CloudTrail `AccessDenied ... ecs:DeregisterTaskDefinition
  on resource: *`. That action has no resource-level or tag scoping, so the tag-conditioned grant never matches. The
  18 task definitions stay "destroying"; the ALB depends on them (its DNS name is in their environment), so the ALB
  stays and the internet gateway cannot detach (`DependencyViolation: mapped public addresses`). The secret version
  was also still destroying. The same stall was very likely behind canary1 runs 3 and 5, masked by the WAF denial.
- Fix **applied, unproven** (your decision: no more canaries): `ecs:DeregisterTaskDefinition` on `*` without the tag
  condition (it cannot be scoped; deregistering only stops new launches); `secretsmanager:GetSecretValue` on
  `convoy-full-*` secrets tagged project=convoy, profile=full (CloudTrail: the provider's secret-version destroy waits
  on GetSecretValue). Trade-off: the reaper can now read a full run's app secrets while the run exists.

> **The reaper is a best-effort backstop.** Its last end-to-end test (canary2) failed; the fix above is applied but not
> proven by a canary. The primary destroy for every full run is `full-down` from the laptop, with the reaper
> scheduled only as a backup. If both fail: `make full-down RUN=<id>` from any machine with the convoy-aws profile.
- Cleanup: reaper task stopped, manual `full.sh down canary2` from the laptop: 32 destroyed, **TEARDOWN VERIFIED 20:35:16 UTC** (nothing tagged run=canary2 remains).
- Smoke run: not started.
- Carriage returns: a Windows-side edit gave `infra/aws/reaper/main.tf` CRLF endings, and the plan would have replaced
  the reaper task definition with a start script ending every line in `
`. Caught at plan, never applied.
  `.gitattributes` now forces LF for `*.sh`, `*.tf`, `Dockerfile*`, `*.yml/*.yaml`, `scripts/**`; CI (`eol.yml`)
  fails on a carriage return in any shell script (22 files clean).
- Canary cost (estimate): ≈ $0.15 (NAT, ALB and IPv4 for ~1.2 h, WAF prorated, five reaper tasks).

**Smoke run `smoke2` (ALB edge, enable_waf=false, 1 task per service, no HA claim): FAILED, torn down, TEARDOWN VERIFIED.**
- Plan 105 to add, 0 change, 0 destroy; RDS db.t4g.medium single-AZ, cache.t4g.small; headroom 2.5 vCPU. Your "apply" 20:39.
- 20:40 reaper backup scheduled (21:40, c178f56); phase 1 applied 74 resources.
- **Failure 1:** one-off `db-init` exited 3 on RDS: `must be able to SET ROLE "identity"` (the RDS admin is not a
  superuser; PostgreSQL 16 requires membership to make a role a schema owner). **Also found:** `full.sh`'s one-off helper
  called `exit` instead of `return`, so the abort (`|| abort`) never ran and the stack stayed up (the reaper backup still
  covered it).
- One fix (7ba94c0): init scripts grant each owner role to the admin and are idempotent (tested twice on PostgreSQL 16
  with a non-superuser admin); the helper returns. Resumed 20:53 (reaper backup 21:53, 7ba94c0): db-init passed,
  services scaled (31 added).
- **Failure 2 (stopped here, no further fix):** every Java service (identity, catalog, pool, dealer, quote, order,
  payment, realtime, notification, audit) exited 1 at startup and restarted; web then failed ALB health checks.
  `services-stable` gave up after 10 min; the abort ran at 21:10 (schedule deleted, destroy). **Root cause unknown:**
  the destroy deleted the run's log group before it was read. Candidates: the prod startup guard or topology check
  refusing something in this environment, or the database path through PgBouncer.
- Teardown: 105 destroyed, **TEARDOWN VERIFIED 21:22:03 UTC**; direct checks: no VPC, RDS, ElastiCache, ALB or schedule.
- No journey ran. Cost (estimate; Cost Explorer lags up to 24 h): ≈ $0.35 (42 min: NAT, ALB, RDS, ElastiCache, IPv4,
  restarting Fargate tasks).
- Next attempt needs: keep the run's log group on abort (so a failed run leaves its evidence), then one more run to read
  the startup error.

**smoke3–smoke5 and the local prod rehearsal (2026-11-01).**
- smoke3 (03:18, 18bd288): db-init passed; Flyway had no password on the direct RDS URL; identity's own URL was
  `localhost`. Aborted and torn down, TEARDOWN VERIFIED 03:52.
- Local prod-profile check added before any AWS run (all 11 services, exact ECS env, RDS-like non-superuser DB): found
  a blank `AWS_ENDPOINT` turned into an empty SNS/SQS endpoint override (every service failed) and notification's
  local-only `VAPID_SUBJECT` default. Also found by reading the ALB config: no route to the simulated payment.
- smoke4 (04:35, 92671be): all services up behind the ALB at 04:49; the journey failed 4 s later (web's server-side
  call to gateway: Service Connect `upstream connect error`). The log group was deleted with the stack before it was
  read: root cause not confirmed. TEARDOWN VERIFIED 05:06.
- smoke5 (05:08): created nothing. An organisation SCP now explicitly denies `scheduler:CreateSchedule`, so the
  reaper backup cannot be scheduled for any full run.
- **Local prod rehearsal** (`deploy/lite/rehearsal`: compose.yml unchanged, prod profile, real AWS shared resources
  with the host keys, PostgreSQL with a NON-superuser `convoy_admin` owning the database, Caddy at convoy.internal):
  found (1) the lite host's IAM policy lacked `sns:GetSubscriptionAttributes` on the topic ARN (SNS authorizes it there):
  every service refused to start; fixed in `shared` (applied on your approval); (2) Caddy's `encode gzip` rewrites ETag
  `"1"` to `"1-gzip"`, web sent `If-Match: "NaN"` and every versioned command (confirm, accept, leave) got 428; fixed in
  `etagVersion` with a test. **Journey then PASSED through Caddy in 4.1 min** (scenario, simulated deposit, 3 dealer
  offers, booked with voucher, dealer view, admin view). Both bugs would have broken the Oracle VM.

**smoke6 (07:56, 885d75f, REAPER=off with an independent Windows Scheduled Task watchdog, tested end to end).**
The laptop lost its connection to S3 during the apply (`request send failed`): Terraform could not save state or
release the lock, the abort's destroy could not run, and the run stopped at your request while offline. Recovered from
Terraform's local `errored.tfstate`; the destroy was resumed three times across further connection drops and an expired
login (stale lock removed each time; four ALB entries already deleted were dropped from the state).
**TEARDOWN VERIFIED 2026-11-01 ~13:10 UTC.** The app was not exercised. Lesson: a full run needs a stable connection
for its whole length; the laptop is a single point of failure while the account's SCP denies the scheduled reaper.

**AWS spend for full-profile runs in P6 (estimates):** smoke1 ≈ $0.18, canary1 ≈ $0.17, canary2 ≈ $0.10, smoke2 ≈ $0.35;
total ≈ **$0.80**; smoke3 ≈ $0.35, smoke4 ≈ $0.40, smoke5 $0, smoke6 ≈ $1.20 (RDS, NAT, ElastiCache live ~4.5 h during the interrupted teardown) → ≈ **$2.75**. Plus `shared` ≈ $4.66/month always on.

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
- **Fargate on-demand vCPU quota is 8**: 2 tasks per service with autoscaling needs a quota increase before P7 (requested).
- The reaper runs on Fargate (CodeBuild's concurrency quota is 0 here).
- First (SQS-only) reaper canary 2026-10-31 (`scripts/reaper-canary.sh`): passed, but proved only scope and scheduling. Scope: the role may delete a convoy/full-tagged
  queue, not convoy/lite or Concord's. The schedule fired at 14:53:45 UTC, the Fargate task checked out exactly
  78ca8f4db9e1…, destroyed the canary queue, logged TEARDOWN VERIFIED (exit 0), and the schedule deleted itself.
- No load test has touched the full stack; autoscaling thresholds (2,000 SSE per task, backlog 200) are first guesses.
- The WAF per-IP rate rule would block load generators at the edge; P7 drives load at the ALB origin (cost estimate §2).
- VPC interface endpoints are not built (NAT carries SQS/SNS traffic); add them if NAT data dominates a run's cost.
- `topology.json` is regenerated by hand; a CI check against a fresh dump would catch drift before deploy.
- PowerUser on the GitHub `full` role is broad (ponytail note in `github.tf`).

## 9. Needs from me
- Decide on the next smoke attempt (retain logs on abort, one more run). P7 waits for a passed smoke run and for the Fargate quota (still 8 vCPU).
- OCI A1 capacity: keep the retry loop running (`cd infra/oci && bash retry-apply.sh`), or upgrade the tenancy to Pay As
  You Go (Always Free limits still apply) to improve capacity odds. Then the lite deploy and §4, §6, §7 follow.
- AWS Support: CloudFront account verification only if a CloudFront edge is wanted later.
