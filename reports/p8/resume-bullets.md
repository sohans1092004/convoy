# Convoy: résumé bullet candidates (P8.F)

Every number comes from [docs/EVIDENCE.md](../../docs/EVIDENCE.md) and keeps its label. Pick 3–5; keep the label
words ("on a laptop", "live") when you use a number.

**Convoy, group car-buying platform** · Java 21, Spring Boot, Next.js 16, PostgreSQL, SNS/SQS, Redis, Terraform, AWS ·
live: https://convoy-main.duckdns.org · code: https://github.com/sohans1092004/convoy

**Product and frontend**
1. Built a mobile-first Next.js app where buyers pool for a car and dealers submit sealed, itemised offers; public demo journey passes on the live site in 4.5 minutes.
2. Cut first-load JavaScript by replacing TanStack Query with a small fetch hook and removing an unused toaster: discover route 185.3 → 164.0 KB gzip (laptop build).

**Distributed correctness**
3. Designed 11 Spring Boot services with per-service schemas and a transactional outbox/inbox over SNS/SQS; six crash and duplicate-delivery tests (MF-1…MF-6) pass in CI.
4. Enforced deadlines on the database clock at commit: of 50 parallel quote submissions around a deadline, every early one was kept and every late one refused (CI).
5. Made concurrent pool creation race-free with a partial unique index plus create-or-find: 50 concurrent creates produce exactly 1 pool (CI).
6. Built SSE realtime with snapshot recovery: after killing an instance under 1,000 streams, all 486 cut streams resynchronised (laptop).

**Money and the saga**
7. Wrote one price engine spec with Java and TypeScript implementations that agree byte for byte on all 84 shared vectors, keeping browser previews identical to server prices.
8. Implemented an orchestrated order saga with explicit compensations; simulated crashes before and after commit at each of its 4 steps yield identical business results (8 runs, CI).
9. Built a double-entry deposit ledger with immutable posted rows and invariants checked after every test; 27/27 deposit outcomes and 30/30 tier vectors pass.

**Security and privacy**
10. Designed BFF sessions with gateway-minted ES256 internal JWTs; 106 authorisation test ids cover 42 endpoints, and revocation closes open streams within 0.6–1.4 s (laptop).
11. (No number; source reports/P5_REPORT.md, not EVIDENCE.md) Shipped GDPR-style export and erasure across services, pseudonymising financial rows and outbox payloads, with an append-only, hash-chained audit log verified by one command.

**Testing**
12. Audited the test suite for checks that could never fail; found and fixed 30 (6 assertions, 24 Storybook page-state stories that rendered errors yet passed).

**Deployment and operations**
13. Deployed with Terraform, multi-arch images by digest and a one-service-at-a-time rollout with automatic rollback; a live redeploy took 268 s and a reboot recovered in 157 s.
14. Kept AWS spend at $0 billed as of Nov 2026 (≈ $2.33 covered by credits) by running the demo lean and verifying teardown after every on-demand full-stack run.
