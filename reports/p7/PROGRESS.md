# P7 progress
- 2026-11-01 03:35 UTC: branch p7/evidence (worktree). Prerequisite (P6 smoke run passed) not yet met: smoke3 found two
  more full-profile config bugs (Flyway credentials on the direct RDS URL; identity's own URL hard-coded to localhost),
  fixed on p6/deploy (d2ea8fe), images rebuilding. Fargate quota still 8 vCPU: P7.A–C at reduced scale; HA drill waits.
- P7.D started (no AWS): first-load JS measured before/after (reports/p7/frontend.md); unused Toaster removed (−11 KB/route).
- P7.A prepared: k6 generator (infra/aws/full/loadgen.tf, LOADGEN_TYPE; reaper may terminate it). Open question: buyer
  sessions at scale on the prod profile (no Mailpit; prod has no test-user path).
