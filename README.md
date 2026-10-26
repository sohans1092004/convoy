# Convoy

Group car buying: buyers pool, dealers quote, the price can only fall. TEST MODE only (Razorpay test keys or the local fake;
no real money). Plan and rules: `PLAN.md`, `SPEC.md`; phase reports in `reports/`.

## Run locally

```bash
bash scripts/dev-env.sh            # writes .env with random local secrets (keeps an existing .env, adds missing keys)
./gradlew bootJar                  # service jars
docker compose up -d --build       # lean stack: one realtime instance, no Jaeger
bash scripts/wait-stack.sh
```

Web: http://localhost:3000 (sign in with a seeded user, e.g. `buyer1@convoy.test`; codes arrive in Mailpit, http://localhost:8025).

### Profiles

| Command | Adds | Use |
|---|---|---|
| `docker compose up -d` | — | everyday work and the single-dealer journey |
| `docker compose --profile tracing up -d` | Jaeger (http://localhost:16686) | the journey's trace assertion: run it with `TRACING=1` |
| `REALTIME_2_URL=http://realtime-2:8080 docker compose --profile ha up -d` | `realtime-2` | two-instance realtime checks (`node tools/realtime/two-instances.mjs`) |

CI's e2e job uses `--profile tracing`; the nightly uses `--profile tracing --profile ha`.

### Demo data

`DEMO=true` (the default for the local stack and CI) loads the synthetic dealerships (`deploy/local/seed/dealerships.sql`)
and the seeded test users (`deploy/local/seed/users.json`). A real environment leaves `DEMO` unset: no demo data in
migrations, no test accounts.

## Tests

```bash
./gradlew test                                     # unit and integration tests (Testcontainers)
cd web && pnpm test && pnpm lint                   # web unit tests and lint
cd web && pnpm exec playwright test e2e/journey.spec.ts --project=mobile-360   # single-dealer journey (stack running)
```

Nightly-only journeys: `THREE_DEALERS=1 LIFECYCLE=1` (full lifecycle) and `CANCEL=1` (dealer cancels after FINAL).
