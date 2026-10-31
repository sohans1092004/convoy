# AWS cost estimate (PLAN §6) — P0, region ap-south-1 (Mumbai)

**Status: estimate.** No AWS resources were created. Unit prices come from the **AWS Price List API** (the public offer files behind the AWS pricing pages and the Pricing Calculator). The files were downloaded on 2026-10-24 and each `publicationDate` is listed in §5. These figures will be replaced by real bills after P6/P7. All amounts are USD and exclude tax. Usage volumes are **ASSUMPTIONS**, stated next to each line.

**Region:** ap-south-1 (Mumbai) puts users, Razorpay and the data in the same country, gives the lowest latency for Indian users, and has every service we need. It is not the cheapest region, but DPDP-related data-location expectations and latency outweigh a few percent of cost.

## 1. Lite profile (always on): monthly, 730 h
| Line | Arithmetic | 4 GB (t4g.medium) | 8 GB (t4g.large) |
|---|---|---|---|
| EC2 on-demand (Graviton) | 0.0224 / 0.0448 $/h × 730 h | 16.35 | 32.70 |
| Public IPv4 address (in use) | 0.005 $/h × 730 | 3.65 | 3.65 |
| EBS gp3 30 GB | 30 × 0.0912 $/GB-mo | 2.74 | 2.74 |
| EBS snapshots (~10 GB incremental) | 10 × 0.05 | 0.50 | 0.50 |
| CloudFront data out, India, 20 GB | 20 × 0.109 | 2.18 | 2.18 |
| CloudFront HTTPS requests, 0.5 M | 50 × 0.012 per 10k | 0.60 | 0.60 |
| EC2 → internet 20 GB (upper bound) | 20 × 0.1093 | 2.19 | 2.19 |
| SQS (idle long-polling dominates) | 25 queues × 3 polls/min × 43,800 min = 3.29 M × 0.40/M | 1.31 | 1.31 |
| SNS publishes 0.1 M | 0.1 × 0.50/M | 0.05 | 0.05 |
| SES 1,000 emails | 1,000 × 0.00016 | 0.16 | 0.16 |
| S3 5 GB | 5 × 0.025 | 0.12 | 0.12 |
| CloudWatch Logs 2 GB ingest + 2 GB stored | 2 × 0.67 + 2 × 0.03 | 1.40 | 1.40 |
| CloudWatch custom metrics (10) | 10 × 0.30 | 3.00 | 3.00 |
| Secrets Manager (5 secrets) | 5 × 0.40 | 2.00 | 2.00 |
| KMS (1 customer key) | 1 × 1.00 | 1.00 | 1.00 |
| ECR 3.3 GB images | 3.3 × 0.10 | 0.33 | 0.33 |
| **Total / month** | | **≈ 37.58** | **≈ 53.94** |

Notes:
- **Free tiers are not applied.** AWS advertises always-free allowances, for example for CloudFront, SQS, SNS and CloudWatch, but they were not re-verified in P0, so the totals are upper bounds. Data transfer from an EC2 origin to CloudFront is commonly documented as free; that was not verified either, so the EC2-to-internet line is kept as an upper bound.
- **Instance size:** the 4 GB vs 8 GB choice is made after measuring idle and peak memory in P6 (PLAN §4.7). Running 11 JVM services plus Postgres and Redis in 4 GB is a stretch: it needs heap caps of about 128–192 MB, SerialGC and CDS, and may not fit.
- **Further saving:** a 1-year Savings Plan or Reserved Instance would cut EC2 cost (not priced here).

## 2. Full profile (on demand): per hour
Sizing assumption: 12 Fargate services (11 + web), 2 tasks each. ARM tasks sized at 0.5–1 vCPU and 1–2 GB, which totals **17 vCPU / 34 GB** at baseline. Under load, autoscaling averages 1.5× (25.5 vCPU / 51 GB).

| Line | Idle (stack up, no load) | $/h | Under load (W-series) | $/h |
|---|---|---|---|---|
| ALB hour | 0.0239 | 0.024 | 0.0239 | 0.024 |
| ALB LCU | 1 × 0.008 | 0.008 | 10 × 0.008 | 0.080 |
| NAT gateway × 2 (one per AZ) | 2 × 0.056 | 0.112 | 2 × 0.056 | 0.112 |
| NAT data processed | 0.2 GB × 0.056 | 0.011 | 2 GB × 0.056 | 0.112 |
| Fargate ARM vCPU | 17 × 0.02383 | 0.405 | 25.5 × 0.02383 | 0.608 |
| Fargate ARM memory | 34 × 0.00261 | 0.089 | 51 × 0.00261 | 0.133 |
| RDS PostgreSQL db.m7g.large **Multi-AZ** | 0.479 | 0.479 | 0.479 | 0.479 |
| RDS gp3 Multi-AZ 50 GB | 50 × 0.262 / 730 | 0.018 | same | 0.018 |
| ElastiCache cache.t4g.small (Redis OSS) × 2 | 2 × 0.041 | 0.082 | same | 0.082 |
| WAF web ACL + 3 rules | (5 + 3) / 730 | 0.011 | same | 0.011 |
| CloudWatch Logs ingest | 0.1 GB × 0.67 | 0.067 | 1 GB × 0.67 | 0.670 |
| CloudWatch custom metrics (200) | 200 × 0.30 / 730 | 0.082 | same | 0.082 |
| X-Ray traces (5 % sampling of 1,000 req/s) | ~0 | 0.000 | 180,000 × 0.000005 | 0.900 |
| SQS incl. retries + DLQs | 0.15 M × 0.40/M | 0.060 | 2 M × 0.40/M | 0.800 |
| SNS publishes | ~0 | 0.000 | 0.2 M × 0.50/M | 0.100 |
| Inter-AZ transfer | 1 GB × 0.02 | 0.020 | 10 GB × 0.02 | 0.200 |
| Public IPv4 (2 ALB + 2 NAT) | 4 × 0.005 | 0.020 | same | 0.020 |
| Load generators 2 × t4g.xlarge | stopped | 0.000 | 2 × 0.0896 | 0.179 |
| **Total** | | **≈ 1.49 /h** | | **≈ 4.61 /h** |

**Edge traffic**, when load is driven through CloudFront + WAF at the W9 rate of 1,000 req/s and 5 KB average:

| Line | Arithmetic | $/h |
|---|---|---|
| CloudFront HTTPS requests 3.6 M/h | 360 × 0.012 per 10k | 4.32 |
| CloudFront data out 18 GB/h | 18 × 0.109 | 1.96 |
| WAF requests 3.6 M/h | 3.6 × 0.60/M | 2.16 |
| **Edge total** | | **≈ 8.44 /h** |

### Representative 6-hour run
**1.5 h idle** (apply, RDS creation, warm-up, teardown) + **4.5 h under load against the ALB origin** + **1 h of that load also through CloudFront + WAF**:

`1.5 × 1.488 + 4.5 × 4.610 + 1 × 8.442 = 2.23 + 20.75 + 8.44 = ` **≈ $31.4 per run**

For comparison, leaving the full stack on idle for a month would cost **≈ $1,086** (1.488 × 730). That is why full is never always-on.

### Biggest cost drivers
1. **Edge requests during load:** CloudFront and WAF request charges total ≈ $6.5/h at 1,000 req/s. Mitigation: drive most workloads at the ALB origin, protected by a secret header, and send only the W9 edge segment through CloudFront.
2. **RDS Multi-AZ:** $0.479/h, about a third of the idle cost. Needed only for the failover claim; single-AZ db.m7g.large is $0.24/h.
3. **Observability under load:** X-Ray ($0.90/h at 5 %; 1 % sampling gives $0.18/h) and log ingest ($0.67/GB). Use INFO-level sampling and no debug logs.
4. **Fargate:** $0.49–0.74/h; ARM is about 44 % cheaper per vCPU than x86 ($0.02383 vs $0.04256).
5. **SQS requests,** including long-polling and retries.

**Not included (UNVERIFIED in P0):** VPC interface endpoints (hourly + per-GB), CloudWatch alarms, dashboards, Route 53, Container Insights, RDS backup storage beyond the free allocation, Razorpay fees (test mode). These are added once priced in P6.

## 3. What the $200 credits do and don't cover
- **The offer:** new accounts created after 15 July 2025 get $100 in credits at sign-up, plus up to $100 more for completing onboarding activities (about $20 each). On the **free plan**, the account expires after 6 months or when credits run out, whichever comes first. On the **paid plan**, credits apply to eligible usage and are usable for up to 12 months. Sources: [AWS What's New](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/), [AWS Billing docs: free tier plans](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html), [AWS News Blog](https://aws.amazon.com/blogs/aws/aws-free-tier-update-new-customers-can-get-started-and-explore-aws-with-up-to-200-in-credits).
- **What they can cover:** roughly 4 months of lite at 4 GB (≈ $150), plus one full run (≈ $31). Or about 6 full runs with no lite at all.
- **What they don't cover:**
  - an always-on full stack (≈ $1,086/month);
  - anything after the credits expire or run out;
  - any service the free plan restricts. Some enterprise-oriented services are restricted on the free plan, and whether ECS Fargate, WAF or Multi-AZ RDS are restricted was not verified. **Assume the paid plan is needed for full runs** (Q-038).
- The credits make no architecture free: PLAN §6's guardrails (budget alerts, scheduled destroy, teardown inventory) still apply.

## 4. Cheapest topology that supports each claim
| Claim | Cheapest topology | Est. cost |
|---|---|---|
| Public working demo | lite on t4g.medium (8 GB only if measured) | ≈ $38–54 /month |
| Messaging failure tests 1–6 | local (LocalStack); optionally lite with real SNS/SQS | $0 / within lite |
| Deadline correctness, W3 counts (correctness only) | local or lite | $0 / within lite |
| Latency W1, W2, W4, W6, W8, W9 at gateway | full with **single-AZ RDS, one NAT, X-Ray 1 %**, load at the ALB origin | ≈ $3.6/h under load (−$0.24 RDS, −$0.06 NAT, −$0.72 X-Ray) |
| SSE 10k / reconnect storm (W5) | same as above; realtime tasks scaled out | ≈ $3.6–4/h |
| Saga under failures (W7) | same as above (Toxiproxy sidecar) | ≈ $3.6/h |
| Failover (RDS Multi-AZ, task/AZ loss) | full as specified (Multi-AZ RDS, 2 NAT) | ≈ $4.6/h |
| WAF-fronted edge behaviour | full + edge segment | + ≈ $8.4/h while driving 1,000 req/s through the edge |

**Recommended P7 plan:** one 6-hour full run for W1–W9 using the cheap variant (Multi-AZ switched on only for a 30-minute failover drill), plus one short edge segment. Budget **≈ $30–35** per run, with a hard cap of $50 enforced by the scheduled destroy and AWS Budgets actions.

## 5. Price sources (AWS Price List API, ap-south-1 offer files, retrieved 2026-10-24)
| Service | Offer file | publicationDate | Prices used |
|---|---|---|---|
| EC2 (instances, NAT, EBS) | https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-south-1/index.json | 2026-10-23 | t4g.medium 0.0224, t4g.large 0.0448, t4g.xlarge 0.0896 $/h; NAT 0.056 $/h + 0.056 $/GB; gp3 0.0912 $/GB-mo; snapshots 0.05 $/GB-mo |
| ECS / Fargate | …/AmazonECS/current/ap-south-1/index.json | 2026-10-19 | ARM vCPU 0.02383, ARM GB 0.00261; x86 vCPU 0.04256, GB 0.004655 $/h |
| ELB | …/AWSELB/current/ap-south-1/index.json | 2026-09-11 | ALB 0.0239 $/h, 0.008 $/LCU-h |
| VPC (public IPv4) | …/AmazonVPC/current/ap-south-1/index.json | 2026-10-16 | 0.005 $/h per in-use IPv4 |
| RDS | …/AmazonRDS/current/ap-south-1/index.json | 2026-10-20 | db.m7g.large PostgreSQL: Single-AZ 0.24, Multi-AZ 0.479 $/h; db.t4g.medium Multi-AZ 0.167 $/h; gp3 0.131 (single) / 0.262 (Multi-AZ) $/GB-mo |
| ElastiCache | …/AmazonElastiCache/current/ap-south-1/index.json | 2026-10-14 | cache.t4g.small Redis 0.041, Valkey 0.0328 $/h |
| WAF | …/awswaf/current/ap-south-1/index.json | 2026-09-14 | 5 $/web ACL-mo, 1 $/rule-mo, 0.60 $/M requests |
| CloudFront | …/AmazonCloudFront/current/index.json (global) | 2026-10-19 | India: 0.109 $/GB first 10 TB; 0.012 $ per 10k HTTPS requests |
| Data transfer | …/AWSDataTransfer/current/ap-south-1/index.json | 2026-09-16 | internet out 0.1093 $/GB (first 10 TB); inter-AZ 0.01 $/GB each direction |
| CloudWatch | …/AmazonCloudWatch/current/ap-south-1/index.json | 2026-10-07 | logs ingest 0.67 $/GB, storage 0.03 $/GB-mo, metrics 0.30 $/metric-mo (first 10k) |
| X-Ray | …/AWSXRay/current/ap-south-1/index.json | 2026-09-11 | 0.000005 $/trace stored, 0.0000005 $/trace accessed |
| SQS | …/AWSQueueService/current/ap-south-1/index.json | 2026-09-11 | standard 0.40 $/M (tier 1), FIFO 0.50 $/M |
| SNS | …/AmazonSNS/current/ap-south-1/index.json | 2026-09-15 | 0.50 $/M API requests |
| SES | …/AmazonSES/current/ap-south-1/index.json | 2026-10-15 | 0.00016 $/outbound email (first 10 M) |
| S3 | …/AmazonS3/current/ap-south-1/index.json | 2026-10-15 | 0.025 $/GB-mo (first 50 TB) |
| Secrets Manager / KMS / ECR | …/AWSSecretsManager, awskms, AmazonECR | 2026-09-11 | 0.40 $/secret-mo; 1 $/key-mo; 0.10 $/GB-mo |

The arithmetic script used to produce these tables is reproduced in [`cost-arithmetic.md`](cost-arithmetic.md) so the numbers can be re-run when prices change.

## 6. P6 lite: Oracle Cloud host + AWS messaging, storage and email in ap-southeast-2 (replaces §1 for the always-on demo)
The account allows only **ap-southeast-2 (Sydney)** (organisation SCP) and is shared with Concord; Convoy's spend is
tracked with the `project=convoy` cost allocation tag and its own budget. Sections 1–5 were estimated for ap-south-1 in P0.
The host is an Oracle Cloud Always Free A1 VM (1 OCPU, 6 GB, 50 GB boot volume, Always Free Object Storage for backups):
**$0**. No EC2, EBS, public IPv4, CloudFront, ECR (images on GHCR), Secrets Manager or KMS on AWS for lite.

Sydney prices (AWS Price List offer files for ap-southeast-2: SQS 2026-09-11, SNS 2026-09-15, CloudWatch 2026-10-07,
SES 2026-10-27): SQS standard 0.40 $/M and FIFO 0.50 $/M (tier 1) after 1 M free requests/month; SNS 0.50 $/M after
1 M free; CloudWatch standard alarm 0.10 $ per alarm metric-month after 10 free; SES 0.00023 $/message; S3 0.025 $/GB-mo;
data out to the internet free up to 100 GB/month. 730 h/month:

| Line | Arithmetic | $/month |
|---|---|---|
| SQS empty long polls (dominant) | 33 queues × 1 poller × 2,628,000 s ÷ 20 s wait = 33 × 131,400 = **4.34 M** receives | |
| SQS DLQ depth checks | 33 DLQs × 2,628,000 s ÷ 900 s (every 15 min) = 0.10 M | |
| SQS real traffic | 2,000 demo scenarios × ~60 events × ~3 consumers = 0.36 M messages; batched receive/delete ≈ 0.5 M requests | |
| SQS total | 4.34 + 0.10 + 0.5 = 4.94 M − 1 M free = 3.94 M × 0.40 $/M, + FIFO share (4 queues × 131,400 = 0.53 M × 0.10 $/M extra) 0.05 | **1.63** |
| SNS publishes | 120 k events ÷ up to 10 per PublishBatch ≤ 0.12 M requests (inside the 1 M free); SNS → SQS delivery is free | 0.00 |
| S3 (exports 24 h, evidence) | < 1 GB × 0.025 | 0.03 |
| SES SMTP | demo accounts are `.invalid` and never mailed; real sign-in only with production access | 0.00 |
| Data transfer out | ~4.4 M SQS responses × ~0.5 KB ≈ 2.2 GB (inside the 100 GB free) | 0.00 |
| CloudWatch alarms | DLQ not empty: 33 alarm metrics; oldest message > 15 min on payment and order queues: 7; 40 − 10 free = 30 × 0.10 (4 + 1 alarms, metric math) | **3.00** |
| AWS Budgets | 1 budget (first 2 free) | 0.00 |
| **Total** | | **≈ 4.66** |

- Long polling is what makes idle messaging cheap: with the local defaults (4 pollers, 1 s waits) the same 33 queues
  would make 33 × 4 × 2,628,000 = 347 M requests ≈ $138/month. Lite sets 1 poller, 20 s, DLQ checks every 15 min.
- Budget alerts at $8 and $20 actual (and $20 forecast), filtered on `project=convoy`: $8 fires only on a real jump
  (a traffic spike or a stuck redelivery loop), not every month.
- The reaper (infra/aws/reaper): CodeBuild general1.small, ~15–40 build minutes per full run, inside the 100 free
  minutes/month; EventBridge Scheduler inside 14 M free invocations; logs < 1 MB. ≈ $0.
- Alarm cost is per metric read, not per alarm: grouping 10 queues into one metric-math alarm saves nothing.

### Full profile in ap-southeast-2: edge options (P6.E)
CloudFront-scoped WAF is not available (it is managed from us-east-1, which the SCP denies), so the WAF is **regional, on
the ALB**. The ALB accepts only CloudFront's origin-facing addresses plus a secret header, so every request passes it.
Keep CloudFront or not:

| Option | HTTPS for browsers | Extra cost per 1-hour smoke run | Under P7 load (3.6 M req/h at the edge) |
|---|---|---|---|
| **Keep CloudFront** (recommended) | yes, `*.cloudfront.net` certificate, no domain needed | ≈ $0 (1 TB and 10 M requests/month always free) | ≈ $4.3/h requests + transfer; P7 drives load at the ALB origin instead |
| Drop CloudFront | only with an ACM certificate, which needs a domain you control (DNS validation in ap-southeast-2 works; DuckDNS cannot hold the CNAME) | $0 | $0 |
Regional WAF either way: 5 $/web ACL-month + 1 $/rule-month prorated (≈ $0.01/h for 2 rules) + 0.60 $/M requests.
Without a domain, dropping CloudFront leaves the stack on plain HTTP, which breaks passkeys and secure cookies; so keep it.
