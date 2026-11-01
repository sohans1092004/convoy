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
