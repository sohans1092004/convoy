# P6 progress

Pre-flight: nightly 40318303340 on `p5/ops` passed; PR #7 merged (2026-10-26 12:48 UTC). The earlier scheduled nightly
failure on `main` (40244851654, `price-breakdown` snapshot) was pre-P5 code; the same suite passed on `p5/ops`, which `main` now equals.

## P6.A: production configuration and images
- `prod` profile: `ProdGuard` (EnvironmentPostProcessor, platform) runs before any bean. Under `prod` it resolves every key of
  the service's own config and lists every problem at once: unresolved `${ENV}`, blank `*password`/`*secret`, a LocalStack
  endpoint, a missing 12-digit `CONVOY_AWS_ACCOUNT_ID`, any `localhost`/`127.0.0.1`/`localstack`/`mailpit`/`convoy.test` value,
  seed users (test logins), SMTP without auth + STARTTLS, malformed URLs. ProdGuardTest 2/2.
- Topology moves to infrastructure (closes P1 deviation 4): under `prod`, `Topology` checks the topic (by ARN in the account),
  queue, DLQ, redrive and subscription, and that the filter policy covers every event type the code consumes. Producers check
  their topics at startup. Anything missing throws `Topology.Missing` naming it, and the service does not start.
  Local and CI still create everything. TopologyCheckIT 1/1 (LocalStack: nothing created in check mode; a lost filter type named).
- Images: `.github/workflows/images.yml` builds amd64 and arm64 on native runners, pushes by digest to GHCR, merges a
  multi-arch index per image and writes `images.env` (`IMAGE_<NAME>=ghcr.io/…@sha256:…`) as an artifact and job summary.
- `deploy/lite/compose.yml`: Caddy (only web + the fake's `checkout.js` and `/fake/pay` public), Postgres, Redis, 1 realtime,
  the simulated payment, memory limits everywhere, JVMs without the OTel agent (SerialGC, C1, 32 MB code cache, 65 % RAM).
- PgBouncer: not needed. `DB_POOL_SIZE=4` × 10 services with a database = 40 connections, plus Flyway at startup and one
  `pg_dump`, under `max_connections=100`.
