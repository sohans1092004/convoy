# P7 progress
- 2026-11-01 03:35 UTC: branch p7/evidence (worktree). Prerequisite (P6 smoke run passed) not yet met: smoke3 found two
  more full-profile config bugs (Flyway credentials on the direct RDS URL; identity's own URL hard-coded to localhost),
  fixed on p6/deploy (d2ea8fe), images rebuilding. Fargate quota still 8 vCPU: P7.A–C at reduced scale; HA drill waits.
- P7.D started (no AWS): first-load JS measured before/after (reports/p7/frontend.md); unused Toaster removed (−11 KB/route).
- P7.A prepared: k6 generator (infra/aws/full/loadgen.tf, LOADGEN_TYPE; reaper may terminate it). Open question: buyer
  sessions at scale on the prod profile (no Mailpit; prod has no test-user path).
- 04:15 UTC: load-test provisioning verified locally: identity's real migrations (platform V0_1–V0_5, identity V1–V6) on
  PostgreSQL 16; provision.py wrote 5 buyers + 1 dealer staff; Java (JCE, same code as identity's Crypto) decrypted 6/6
  emails and matched 6/6 HMAC lookup hashes; 6/6 session tokens hash to stored sessions; auth_method otp, TTL 4 h, one
  UserRegistered outbox event per user. W9 k6 script parses (k6 0.57 inspect: 6 scenarios, thresholds per named route).
- 15:10 UTC: P7.D done for the targets: TanStack Query replaced by lib/use-fetch.ts; discover 164.0, model 167.7, pool
  168.1 KB (cap 170, all met); order 178.4 KB still over. Typecheck, ESLint, 28/28 unit tests pass; browser journeys
  against this build and Lighthouse LCP still to run (need the local stack).
- Load clients through the ALB authenticate like the browser (cv_sid cookie + CSRF double-submit + same-origin Origin):
  the BFF does not forward X-Convoy-Session, so the first W9 draft would have been all 401s. Gateway rate limits are
  per user except OTP/demo (per IP), so one generator IP is not throttled.
- P7.B W5 on AWS ready (tools/load/w5_aws.mjs, `scripts/p7-load.sh <run> w5`): 10,000 streams via the ALB, storm by
  stopping a realtime task through the ECS API, slow consumers; memory per 1,000 connections from CloudWatch.
- P7.B W4 not built yet: the seed has 4 dealerships (Pune, Maruti); 50 pools x 20 dealers needs load-test dealerships
  provisioned in the dealer schema (extend provision.py) and a longer sealed round for that run (DEMO_SEALED_ROUND_SECONDS).
- P6 smoke status: smoke7/8 found Service Connect names unresolvable in a task's first seconds (UnknownHostException
  pgbouncer); fixed by core-first start + 90 s DB connect retry; smoke9 pending. Laptop DNS (single carrier resolver)
  caused the "request send failed" errors; DNS now 1.1.1.1/8.8.8.8.
