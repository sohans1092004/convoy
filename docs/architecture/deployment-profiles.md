# Deployment profiles (PLAN §4.7, ADR-005)

Three profiles, one codebase, the same container images. Costs: [`../cost/aws-cost-estimate.md`](../cost/aws-cost-estimate.md).

## local (development)
```mermaid
flowchart LR
  B[Browser] --> W[web :3000]
  W --> G[gateway :8080]
  G --> SVC[8 wave-1 services\n+ realtime, notification, audit\nreduced replicas]
  SVC --> PG[(PostgreSQL 16\nschema per service)]
  SVC --> R[(Redis)]
  SVC --> LS[LocalStack\nSNS / SQS / S3 / SES]
  P[payment] -.test mode.-> RZ[Razorpay test API]
```
Docker Compose. LocalStack for SNS/SQS/S3/SES; Razorpay test mode (webhooks via a tunnel or replayed fixtures).
- **May claim:** functional correctness, contract tests, messaging failure tests (§4.3) on LocalStack.
- **May not claim:** any latency, capacity, availability or cost figure.

## lite (always-on demo)
```mermaid
flowchart LR
  B[Browser] --> CF[CloudFront]
  CF --> C[EC2: Caddy]
  subgraph EC2 ["single EC2 host (4 GB, 8 GB if measured need)"]
    C --> W[web]
    C --> G[gateway]
    G --> SVC[all 11 services\ncontainer mem limits\nSerialGC, CDS, heap caps]
    SVC --> PG[(PostgreSQL on host)]
    SVC --> R[(Redis on host)]
  end
  SVC --> AWS[SNS / SQS / S3 / SES]
```
No ALB, no WAF, no NAT gateway (public subnet, security group allows only CloudFront origin-facing prefix list on 443 + SSM for admin; no SSH). Instance size chosen **after measuring** idle and peak memory in P6.
- **May claim:** "a working public demo", end-to-end journey, real AWS messaging integration, functional demos of failure isolation on one host.
- **May not claim:** availability, scale, latency SLOs, failover, multi-AZ, WAF protection, any load-test number.

## full (on demand, budgeted)
```mermaid
flowchart LR
  B[Browser] --> CF[CloudFront] --> WAF[AWS WAF] --> ALB[ALB 2 AZ]
  subgraph VPC
    ALB --> W[web tasks x2]
    ALB --> G[gateway tasks x2]
    G --> SC[ECS Service Connect]
    SC --> SVC[11 services, >= 2 tasks each\nautoscaling]
    SVC --> RDS[(RDS PostgreSQL Multi-AZ)]
    SVC --> EC[(ElastiCache Redis)]
    SVC --> NAT[NAT gateway] --> RZ[Razorpay test API]
  end
  SVC --> MSG[SNS / SQS / S3 / SES]
  SVC --> OBS[ADOT -> CloudWatch / X-Ray]
```
ECS Fargate across 2 AZs, ≥ 2 tasks per service, autoscaling on the scaling signal in services.md, RDS Multi-AZ, ElastiCache, ADOT/X-Ray. VPC endpoints for S3/SQS/SNS/ECR/Secrets Manager/CloudWatch (interface endpoints cost money — see cost estimate) to cut NAT data. Applied for a run, verified, destroyed with a teardown inventory check (PLAN §6).
- **May claim (only with measured evidence from a run):** W1–W9 capacity and latency, SSE 10k, failover (task kill, AZ task loss, RDS failover), isolation (notification/realtime/audit down), WAF-fronted edge.
- **May not claim:** 24×7 availability (it is not always on), production readiness, real-money behaviour.

| Claim | local | lite | full |
|---|---|---|---|
| Journey works end to end | ✓ | ✓ | ✓ |
| Messaging failure tests 1–6 | ✓ (LocalStack) | ✓ | ✓ |
| Latency p95/p99 (W9) | ✗ | ✗ | ✓ |
| 10k SSE / reconnect storm (W5) | ✗ | ✗ | ✓ |
| Deadline synchronised arrivals (W4) correctness | ✓ (correctness only) | ✓ (correctness only) | ✓ (+ throughput) |
| Failover / Multi-AZ | ✗ | ✗ | ✓ |
| Isolation of non-core services | ✓ (functional) | ✓ (functional) | ✓ (under load) |
| Cost per run | — | monthly | per hour / per run |
