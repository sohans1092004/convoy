#!/usr/bin/env bash
# P6.A laptop measurement of deploy/lite/compose.yml (LocalStack + Mailpit via measure.yml, local profile):
# cold start (up -> every container healthy), idle memory after 2 min, peak memory during one journey.
# JOURNEY=0 skips the journey. Laptop numbers only; VM numbers are measured separately in P6.D. Output: reports/p6/lite-measure-<ts>/
set -euo pipefail
cd "$(dirname "$0")/.."
sep=':'; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=';' ;; esac
export COMPOSE_FILE="deploy/lite/compose.yml${sep}deploy/lite/measure.yml" COMPOSE_PATH_SEPARATOR="$sep"
export COMPOSE_ENV_FILES=.env,deploy/lite/measure.env COMPOSE_PROJECT_NAME=convoy-lite
out=reports/p6/lite-measure-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$out"
stats() { docker stats --no-stream --format '{{.Name}},{{.MemUsage}},{{.CPUPerc}}' $(docker compose ps -q); }

docker compose down -v --remove-orphans >/dev/null 2>&1 || true
t0=$(date +%s)
docker compose up -d >/dev/null
until [ -z "$(docker compose ps --format '{{.Name}} {{.Health}}' | grep -vE ' healthy$| $')" ]; do
  [ $(( $(date +%s) - t0 )) -gt 600 ] && { docker compose ps; echo "not healthy after 600 s"; exit 1; }
  sleep 2
done
echo "cold_start_seconds=$(( $(date +%s) - t0 ))" | tee "$out/cold-start.txt"

sleep 120
stats > "$out/idle.csv"
# JOURNEY=0: idle only (a laptop without ~5 GB spare; the journey peak is then measured on the VM, P6.D)
[ "${JOURNEY:-1}" = 0 ] && { docker compose down -v >/dev/null 2>&1; echo "$out"; exit 0; }

( while :; do stats >> "$out/journey-samples.csv"; sleep 3; done ) & sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT
set +e
(cd web && WEB_URL=http://localhost pnpm exec playwright test e2e/journey.spec.ts --project=mobile-360) > "$out/journey.log" 2>&1
rc=$?
set -e
kill $sampler; echo "journey_exit=$rc" | tee -a "$out/cold-start.txt"
echo "$out"
