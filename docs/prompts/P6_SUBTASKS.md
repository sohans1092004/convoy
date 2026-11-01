# Convoy Phase 6: deployment (6 subtasks)

**Before starting:** run the nightly on `p5/ops`, fix anything it finds, then merge PR #7. Start a fresh agent session for each subtask.

**Targets:**
- **lite (always on, free):** an Oracle Cloud Always Free ARM VM (1 OCPU, 6 GB; the other half of the free allowance is kept for Reliant) running Convoy with Docker Compose behind Caddy. It uses real AWS services for messaging, storage and email.
- **full (on demand, budgeted):** the multi-zone ECS Fargate stack on AWS, brought up only for P7's load tests and failure drills, then destroyed.

## Working style (paste above every subtask)

```
You are working on Convoy Phase 6 (deployment), one subtask at a time. Read PLAN.md (§4.7, §6),
SPEC.md (+ amendments), docs/architecture/deployment-profiles.md, docs/cost/aws-cost-estimate.md,
reports/P5_REPORT.md and reports/p6/PROGRESS.md (create if missing) first.
Branch: p6/deploy (from main). Draft PR on the first push.

Rules for this phase:
- Nothing that costs money is created without my explicit "apply" after I've seen the plan and its
  cost. You write Terraform and scripts; I run `terraform apply` myself unless I say otherwise.
- Every cloud resource is tagged (project=convoy, profile=lite|full). Before any apply, show the
  plan summary and the estimated monthly or hourly cost.
- Oracle: Always Free resources only (A1 limit is 2 OCPU / 12 GB per tenancy; Convoy gets
  1 OCPU / 6 GB). Stop if the plan includes anything that isn't Always Free.
- No secrets in git. Host secrets live in a git-ignored env file on the VM; CI uses GitHub
  environment secrets; AWS from GitHub uses OIDC (no stored keys).
- Numbers measured on the VM are reported as VM numbers, never mixed with laptop numbers.
- Same working style as before: build first, targeted tests, 2-attempt rule, 60-minute time box,
  progress lines every ~20 minutes, short report: DONE / TESTS RUN / PROBLEMS / NEEDS FROM ME.
```

---

## P6.A: Production configuration and images
- **`prod` profile:** no LocalStack, Mailpit or demo shortcuts. Real AWS endpoints for SNS, SQS and S3; email over SMTP (AMENDMENT-011) to the SES SMTP endpoint; config entirely from environment variables, validated at startup (fail fast on missing or invalid values).
- **Messaging topology moves to infrastructure:** services no longer create topics and queues at startup in `prod`. They check that what they need exists and refuse to start with a clear error if not (the P1 deviation 4 is closed). Local and CI still create them.
- **Multi-arch images:** GitHub Actions builds `amd64` and `arm64` images for every service and the web app, pushes them to GHCR, and records digests. Deploys use digests, never `latest`.
- **`deploy/lite/compose.yml`:**
  - 1 realtime instance, no LocalStack, Mailpit or Jaeger
  - Postgres, Redis and Caddy
  - explicit memory limits for every container, and JVM settings tuned for small heaps
  - PgBouncer in front of Postgres if the connection budget (P2.5 note) exceeds `max_connections`; otherwise document why it isn't needed
- **Measure on the laptop** with `deploy/lite/compose.yml` (against LocalStack for this one run): idle and journey-peak memory per container and in total, and cold start. If the total exceeds about 5 GB, tell me the options before going further.

## P6.B: Public demo mode (how strangers try it safely)
- **"Try the demo" button:** creates an **isolated scenario** for that visitor:
  - a fresh temporary buyer account
  - a fresh pool for a verified model in the demo city
  - simulated dealers that quote on short demo durations
  - a dealer view of one simulated dealership, so visitors can try the quote builder

  No email is needed (the SES sandbox can't send to arbitrary addresses).
- **Admin view:** a read-only "view as admin" with synthetic data. Real admin powers still require a passkey and aren't offered publicly.
- **Limits:**
  - scenarios expire after 24 hours, and a cleanup job removes them with their data
  - per-IP and global caps (for example 20 new scenarios per IP per day and 200 per day in total) through the existing rate limiter
  - a clear 503 message when the cap is reached
- **Banner on every page:** "Demo: synthetic dealers, test payments, no real money".
- **Payments in the public demo:** use the **built-in simulated payment** (the Razorpay fake), clearly labelled. The real Razorpay test-mode account has refunds disabled, so refund flows would get stuck. If I later enable refunds, real test mode can be switched on by config.
- **Real email sign-in:** keep it in the code, enabled only when SES production access exists (a config flag).

## P6.C: AWS resources used by the lite profile (Terraform, applied by me)
- **`infra/aws/shared/`:**
  - the SNS topics and SQS queues exactly matching the event catalogue (FIFO where specified, DLQs, redrive policies, alarms on DLQ depth and oldest-message age)
  - S3 buckets for exports and dispute evidence: private, encrypted, public access blocked, exports expire after 24 h
  - SES: a domain identity only if I have a domain; otherwise skipped and documented
  - an IAM user for the lite host with **least-privilege** access to exactly these resources
  - AWS Budgets alerts at $5 and $20
- **Cost estimate** for the lite AWS usage: SQS request volume from long polling × number of queues, SNS, S3, data transfer. Show the arithmetic.
- **Key rotation:** document how to rotate the lite host's access key.

## P6.D: Oracle Cloud VM and the live demo
- **`infra/oci/`:** Terraform for a VCN, a public subnet, a security list (SSH from my IP only; 80 and 443 open) and one A1.Flex VM (1 OCPU, 6 GB, Ubuntu 24.04 aarch64, 50 GB boot volume), with SSH keys only.
  - If OCI returns "Out of host capacity", retry in the home region; don't change region or shape.
- **`bootstrap.sh` (idempotent):**
  - unattended upgrades, fail2ban, SSH hardening
  - opening 80 and 443 in the VM's iptables too
  - a 2 GB swap file
  - Docker
  - Caddy with Let's Encrypt for a DuckDNS subdomain I'll create
- **`deploy.sh <sha>`:** back up Postgres, pull images by digest, run migrations, restart services one at a time, smoke-test, and roll back automatically on failure. GitHub Actions runs it over SSH as a restricted `deploy` user whose key can only run that script.
- **Backups:** nightly `pg_dump`, encrypted with `age`, sent to OCI Object Storage (Always Free), keeping 7. **Do one restore** into a scratch database and compare row counts.
- **Checks on the live VM (recorded):**
  - the demo journey passes against the public URL
  - the VM reboots back to healthy
  - from outside, only ports 80 and 443 are open
  - `/metrics`, Prometheus and admin internals are unreachable
  - TLS is valid
  - memory per container, idle and during the demo
- Note Oracle's idle-reclamation rule: if memory use stays above 20%, the VM doesn't meet the idle criteria. Never generate fake load.

## P6.E: Full AWS profile (on demand)
- **`infra/aws/full/`:**
  - networking: a VPC across 2 AZs, ALB, and CloudFront plus WAF in front
  - compute: ECS Fargate services, each with 2+ tasks and autoscaling on its own signal (realtime on connections, notification on queue depth, others on CPU), with ECS Service Connect
  - data: ElastiCache, RDS Postgres (**single-AZ by default; a Multi-AZ flag only for the failover drill**) and PgBouncer
  - operations: ADOT and X-Ray with sampling, Secrets Manager, and a GitHub OIDC deploy role
  - it reuses `shared/` for SNS, SQS and S3
- **`make full-up` / `make full-down`:**
  - a **maximum duration** enforced by a scheduled destroy
  - after destroy, **teardown verification**: a tag-based inventory must be empty
  - a cost report (from Cost Explorer once available, otherwise the estimate)
- **One smoke run, at most 1 hour:** apply, run the single-dealer journey against it, then destroy and verify the teardown. Record the actual cost. No load tests; those are P7.

## P6.F: Report
Write `reports/P6_REPORT.md` with these headings:
1. Summary, with the live demo URL
2. Built (A–E)
3. Deviations
4. Memory and CPU on the VM
5. The full-profile smoke run, with its cost
6. Security checks
7. Backups and the restore result
8. Gaps for P7
9. Needs from me

Mark the PR ready for review.

---

**Your actions during P6** (the agent will ask at the right moment):
1. Create the Oracle API signing key (`~/.oci`), and confirm the tenancy's limits page shows the A1 Always Free limits.
2. Create a DuckDNS subdomain (for example `convoy-demo.duckdns.org`).
3. Set up the AWS account (Free plan, region `ap-south-1`) with a budget alert, then run `terraform apply` for `shared/` after reviewing the plan.
4. Add the GitHub environment secrets the agent lists.
5. Optional: a real domain, if you want SES email sign-in for real users.
