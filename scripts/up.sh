#!/usr/bin/env bash
# Local stack in one command: .env with random local secrets, service jars, the lean compose stack, wait until healthy.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/dev-env.sh
./gradlew bootJar
docker compose up -d --build
bash scripts/wait-stack.sh
