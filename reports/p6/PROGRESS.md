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

## P6.B: public demo mode
- **Try the demo** (home page and sign-in page, `DEMO_PUBLIC=true`): identity E170 creates an isolated scenario with no
  email: a temporary buyer and the admin of a fresh demo dealership (dealer E172), both role `demo`, sessions of 24 h,
  addresses under `.invalid` (notification marks their emails SKIPPED). The web app then creates a fresh pool for the
  Swift in Pune and opens it. "Switch to dealer view" swaps to the dealership admin, for the quote builder.
- **Isolation:** pool keeps a demo buyer's pools to that buyer (`demo_owner`; never found, joined by create, or suggested
  to anyone else). E055 takes the pool's owner: a visitor's dealership is invited only to that visitor's pools; the shared
  simulated dealers are invited everywhere.
- **Simulated dealers:** `tools/simulator/demo-bots.mjs` (own image) runs the P3.F simulator for every sealed round,
  signing in through identity E171 (client `demo-bots`, scope `demo:sessions`, simulated dealers only). Demo durations as before.
- **Admin:** `/demo/admin` renders the real analytics component with fixed synthetic figures; no admin API is called.
  Real admin needs a passkey (unchanged).
- **Limits:** gateway caps E170 at 20 per IP per day (429) and 200 per day in total (503 `demo-capacity-reached`,
  "The demo is full for today"). Expiry: identity's hourly job sends scenarios older than 24 h through the P5.D erasure flow
  (every service deletes or pseudonymises) and ends their sessions; dealer removes demo dealerships older than 24 h.
- **Banner** on every page: "Demo: synthetic dealers, test payments, no real money". Payments use the built-in fake.
- **Email sign-in** stays in the code; `EMAIL_SIGN_IN=false` (lite default) returns 503 `email-sign-in-disabled`.
  Passkey sign-in still works for real admins.
- Tests: DemoIT 4/4, IdentityIT 11/11, DealerIT 7/7 (demo dealership eligibility + expiry), PoolIT 20/20 (demo pool
  isolation), GatewayIT 5/5, FailFastTest 2/2, NotificationIT 5/5, web unit 26/26, tsc + eslint clean, validate_spec 14/14.

## P6.C-E: infrastructure (validated with terraform validate, nothing applied)
- `infra/aws/shared`, `infra/oci`, `infra/aws/full`, host scripts, `deploy-lite` / `full` / `infra` workflows. Details and
  deviations: reports/P6_REPORT.md. Waiting on the applies listed there (section 9).
