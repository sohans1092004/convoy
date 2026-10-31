# Full profile runbook (P6.E)

The multi-AZ stack (`infra/aws/full`): CloudFront + WAF, ALB over 2 AZs, ECS Fargate (ARM) with Service Connect and
>= 2 tasks per service, autoscaling (realtime on SSE connections, notification on queue backlog, others on CPU),
RDS PostgreSQL (single-AZ; `multi_az = true` only for the failover drill) behind PgBouncer, ElastiCache Redis,
ADOT -> X-Ray (5 % sampling), one Secrets Manager secret per run. SNS/SQS/S3 come from `infra/aws/shared`.
Only for P7's load tests and failure drills; ≈ $1.49/h idle (cost estimate §2; one NAT and single-AZ RDS make it less).

## Runner (decision pending)
The account's organisation SCP denies `iam:CreateOpenIDConnectProvider`, so GitHub Actions cannot assume a role, and
stored keys are not allowed. Runs therefore go from the laptop with the `convoy-aws` session (`make full-up` /
`make full-down`, `scripts/full.sh`). The maximum duration needs a destroy that does not depend on the laptop; the
proposal is an in-account CodeBuild project (started by an EventBridge Scheduler one-time schedule at the deadline)
that runs `scripts/full.sh down` with its own role and reads the state from `convoy-tfstate-<account>`.

## A run (from the laptop)
`make full-up RUN=r1 IMAGES=images.env` with `SHARED_JSON` and `SECRETS_JSON` exported (see `scripts/full.sh`):
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
