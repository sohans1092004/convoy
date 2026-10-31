# Full profile runbook (P6.E)

The multi-AZ stack (`infra/aws/full`): CloudFront + WAF, ALB over 2 AZs, ECS Fargate (ARM) with Service Connect and
>= 2 tasks per service, autoscaling (realtime on SSE connections, notification on queue backlog, others on CPU),
RDS PostgreSQL (single-AZ; `multi_az = true` only for the failover drill) behind PgBouncer, ElastiCache Redis,
ADOT -> X-Ray (5 % sampling), one Secrets Manager secret per run. SNS/SQS/S3 come from `infra/aws/shared`.
Only for P7's load tests and failure drills; ≈ $1.49/h idle (cost estimate §2; one NAT and single-AZ RDS make it less).

## Runner and scheduled destroy
No GitHub runner (the SCP denies the OIDC provider; no stored keys). Runs go from the laptop with the `convoy-aws`
session. The maximum duration is enforced in the account: `scripts/full.sh up` first creates a one-time EventBridge
Scheduler schedule (`scripts/schedule-reap.sh`, default 60 minutes) that runs the `convoy-reaper` ECS Fargate task
(`infra/aws/reaper`, 0.25 vCPU, own small VPC). The task checks out exactly the commit the run was applied from (a
40-character sha; `full.sh up` refuses uncommitted `infra/` or `scripts/` changes) and runs `scripts/reap.sh`:
`terraform destroy`, teardown verification (no resource tagged with the run), and a report to `convoy-alerts` (email).
Its role may change or delete only resources tagged `project=convoy` and `profile=full`, plus the full-profile state
objects; it is itself tagged `profile=lite`, so it cannot delete itself. `make full-down` runs the same `reap.sh` and
removes the schedule. Tested end to end by `scripts/reaper-canary.sh` (one empty SQS queue) before a run relies on it.
(Fargate, not CodeBuild: this account's CodeBuild concurrency quota is 0.)

**Headroom for the reaper:** `full.sh up` refuses to start unless the Fargate on-demand vCPU quota, minus Fargate tasks
already running, minus this run's worst case (every service at its autoscaling ceiling, + 0.25 for db-init/seed),
leaves at least 0.5 vCPU, so the reaper can always launch. With the current quota of 8 vCPU:

| Setting | Worst case | Fits? |
|---|---|---|
| `TASKS_PER_SERVICE=1 MAX_TASKS_PER_SERVICE=1` (P6 smoke run) | 5.50 vCPU (16 services, 0.25–0.5 vCPU each, + 0.25) | yes, 2.50 left |
| `TASKS_PER_SERVICE=2 MAX_TASKS_PER_SERVICE=2` (HA, no autoscaling) | 10.25 vCPU | no: quota first |
| defaults (2, autoscaling to 6) | 29.25 vCPU | no: quota first (P7) |

**If both the reaper and the laptop fail** (the schedule did not fire, the task failed, the laptop is off), the manual
fallback is `make full-down RUN=<run-id>` from **any machine** with the `convoy-aws` profile logged in (`aws login`):
it runs the same `reap.sh` against the state in `s3://convoy-tfstate-<account>/full/`. The run id is the `run` tag on
any resource of the run (`aws resourcegroupstaggingapi get-resources --tag-filters Key=profile,Values=full`).

## A run (from the laptop)
`TASKS_PER_SERVICE=1 MAX_TASKS_PER_SERVICE=1 make full-up RUN=r1 IMAGES=images.env` for the P6 smoke run, with
`SHARED_JSON` and `SECRETS_JSON` exported (see `scripts/full.sh`). One task per service makes **no availability claim**;
the failover and HA drills need 2 tasks per service (P7, after a quota increase).
1. applies with every service at 0 tasks, runs `db-init` (schemas and roles, the same scripts as local), applies again
   with 2 tasks each, waits for services to be stable, seeds the synthetic dealerships, prints the CloudFront URL;
2. `cd web && WEB_URL=<url> REMOTE=1 pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360` (smoke);
3. `make full-down RUN=r1`: destroy, teardown verification (no resource tagged with the run, retried 15 min), then
   `scripts/full.sh cost r1 <hours>`.

## Manual recovery (interrupted apply or destroy)
1. Re-run `make full-down`. Terraform resumes from the state in `s3://convoy-tfstate-<account>/full/`.
2. A stale lock: `terraform force-unlock <id>` in `infra/aws/full` (after `terraform init -backend-config=bucket=…`).
3. Still listed after destroy: `scripts/full.sh verify <run>` prints the ARNs; delete them in the console (usual
   stragglers: ENIs of stopped tasks, CloudWatch log groups, the WAF web ACL), then verify again.
4. The secret is created with `recovery_window_in_days = 0`, so it is deleted immediately and never blocks a re-run.
