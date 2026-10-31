# Full profile runbook (P6.E)

The multi-AZ stack (`infra/aws/full`): CloudFront + WAF, ALB over 2 AZs, ECS Fargate (ARM) with Service Connect and
>= 2 tasks per service, autoscaling (realtime on SSE connections, notification on queue backlog, others on CPU),
RDS PostgreSQL (single-AZ; `multi_az = true` only for the failover drill) behind PgBouncer, ElastiCache Redis,
ADOT -> X-Ray (5 % sampling), one Secrets Manager secret per run. SNS/SQS/S3 come from `infra/aws/shared`.
Only for P7's load tests and failure drills; ≈ $1.49/h idle (cost estimate §2; one NAT and single-AZ RDS make it less).

## One-time setup
1. `infra/aws/shared` applied (state bucket and `convoy-github-full` OIDC role come from it).
2. GitHub environment `full` (add yourself as required reviewer, so every run waits for your approval):
   - variable `AWS_ROLE_ARN` = `terraform output -raw github_full_role_arn` (shared)
   - variable `SHARED_JSON` = `{"account_id":"…","export_bucket":"…","evidence_bucket":"…","smtp_host":"…","smtp_username":"…","mail_from":"…"}`
   - secret `SECRETS_JSON` = every key in `deploy/lite/convoy.env.example` from POSTGRES_PASSWORD to RAZORPAY_WEBHOOK_SECRET,
     plus SMTP_PASSWORD (fresh random values, not the lite host's)
3. Cost Explorer: activate the `run` cost allocation tag once (Billing, Cost allocation tags); it takes up to 24 h.

## A run
`make full-up HOURS=1`: the `full` workflow
1. applies with every service at 0 tasks, runs `db-init` (schemas and roles, the same scripts as local), applies again
   with 2 tasks each, waits for services to be stable, seeds the synthetic dealerships, prints the CloudFront URL;
2. runs the public demo journey against it (smoke);
3. **guard**: sleeps until `HOURS`, destroys, verifies the teardown (no resource tagged with the run in ap-south-1 or
   us-east-1, retried for 15 min) and reports the cost (Cost Explorer by tag, else the estimate).
`make full-down` destroys at once; the guard then finds nothing.

## Manual recovery (interrupted apply or destroy)
1. Re-run `make full-down`. Terraform resumes from the state in `s3://convoy-tfstate-<account>/full/`.
2. A stale lock: `terraform force-unlock <id>` in `infra/aws/full` (after `terraform init -backend-config=bucket=…`).
3. Still listed after destroy: `scripts/full.sh verify <run>` prints the ARNs; delete them in the console (usual
   stragglers: ENIs of stopped tasks, CloudWatch log groups, the WAF ACL in us-east-1), then verify again.
4. The secret is created with `recovery_window_in_days = 0`, so it is deleted immediately and never blocks a re-run.
