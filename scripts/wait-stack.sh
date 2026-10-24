#!/usr/bin/env bash
# Wait until every service reports UP and the web answers; prints the time it took.
set -euo pipefail
cd "$(dirname "$0")/.."
start=$(date +%s)
for _ in $(seq 1 120); do
  ok=0
  for s in identity catalog dealer pool quote order payment; do
    docker compose exec -T "$s" wget -qO- localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"' && ok=$((ok + 1))
  done
  if [ "$ok" = 7 ] && [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:3000/)" = 200 ]; then
    echo "stack ready in $(( $(date +%s) - start ))s"
    exit 0
  fi
  sleep 3
done
echo "stack not ready after 6 minutes" >&2
docker compose ps
exit 1
