# ADR-005: Three deployment profiles with explicit claims

**Status:** Accepted

## Context
The project needs a public, always-on demo at very low cost. It also needs capacity, failover and isolation evidence, which only a multi-AZ, autoscaled stack can support. New AWS accounts get up to $200 in credits for up to 6 months, but that does not make any particular architecture free (PLAN §6).

## Decision
Three profiles:
- **local:** Docker Compose plus LocalStack.
- **lite:** CloudFront → a single EC2 host running Caddy, with all services in containers, Postgres and Redis on the host, and real SNS/SQS/S3/SES. No ALB, WAF or NAT.
- **full:** CloudFront → WAF → ALB → ECS Fargate across 2 AZs, with RDS Multi-AZ, ElastiCache and ADOT/X-Ray.

deployment-profiles.md has a table of which claims each profile may and may not support. Full runs are planned, time-boxed experiments, with a scheduled destroy and teardown verification.

## Alternatives considered
- **Only full, always on:** an honest capacity story, but roughly an order of magnitude more per month than lite (see the cost estimate). Unaffordable for a personal project.
- **Only lite:** cheap, but cannot support any availability or scale claim.
- **Kubernetes (EKS) for full:** portable and popular, but the EKS control plane alone bills hourly and adds operational surface. ECS Fargate meets the need.
- **Serverless (Lambda) services:** low idle cost, but long-lived SSE connections, JVM cold starts and Postgres connection pooling all fit poorly.

## Consequences
- Every report states which profile it ran on. Numbers from lite or local are never presented as capacity evidence.
- The two Terraform stacks (lite and full) share modules. Drift between them is a known risk, mitigated by running the same images and the same messaging resources in both.
