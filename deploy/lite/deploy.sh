#!/usr/bin/env bash
# Deploy one commit to the lite host (P6.D):
#   deploy.sh <sha> <images.env>   (normally via deploy-ssh.sh from GitHub Actions)
# 1. back up Postgres
# 2. check out <sha> (compose file, Caddyfile, scripts)
# 3. pull images by digest
# 4. restart services one at a time, each until healthy (Flyway migrations run as each service starts)
# 5. smoke-test through Caddy
# Any failure rolls back to the previous commit and images and waits for health again.
# Migrations must stay backward compatible (expand, then contract in a later release), so old images run on the new
# schema. The pre-deploy dump is there for a manual restore (docs/runbooks/lite-host.md), never applied automatically.
set -euo pipefail
sha=$1 images=$2
cd /opt/convoy/src
state=/opt/convoy/deployed   # sha + images.env of the running release
compose() { docker compose --env-file /opt/convoy/convoy.env --env-file "$1" -f deploy/lite/compose.yml "${@:2}"; }
log() { echo "$(date -u +%FT%TZ) $*"; }
set -a; . /opt/convoy/convoy.env; set +a

ORDER=(postgres redis identity catalog dealer pool quote order payment realtime-1 notification audit gateway razorpay-fake demo-bots web caddy)

wait_healthy() { # service, env file
  for _ in $(seq 1 90); do
    id=$(compose "$2" ps -q "$1")
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo missing)
    [[ "$st" == healthy || ( "$st" == running && -z "$(docker inspect -f '{{.State.Health}}' "$id" | grep -v '<nil>')" ) ]] && return 0
    sleep 2
  done
  log "$1 not healthy (last state: $st)"; return 1
}

roll_out() { # env file
  compose "$1" pull -q
  for s in "${ORDER[@]}"; do
    compose "$1" up -d --no-deps "$s"
    wait_healthy "$s" "$1" || return 1
  done
}

smoke() {
  curl -fsS --max-time 10 "https://${SITE_ADDRESS}/" -o /dev/null &&
    curl -fsS --max-time 10 "https://${SITE_ADDRESS}/api/catalog/models?city=Pune" | grep -q '"slug"' &&
    [ "$(curl -s -o /dev/null -w '%{http_code}' "https://${SITE_ADDRESS}/actuator/health")" != 200 ]  # internals stay unreachable
}

prev_sha=$(cat "$state/sha" 2>/dev/null || true)
log "deploy $sha (running: ${prev_sha:-none})"
[ -n "$prev_sha" ] && deploy/lite/backup.sh pre-deploy
git fetch -q origin && git checkout -q --detach "$sha"
mkdir -p "$state"
cp "$images" "$state/images.next.env"

if roll_out "$state/images.next.env" && smoke; then
  mv "$state/images.next.env" "$state/images.env"
  echo "$sha" > "$state/sha"
  docker image prune -af --filter "until=168h" >/dev/null
  log "deployed $sha"
  exit 0
fi

log "FAILED: rolling back to ${prev_sha:-nothing}"
[ -n "$prev_sha" ] || exit 1
git checkout -q --detach "$prev_sha"
roll_out "$state/images.env" && smoke && log "rolled back to $prev_sha" || log "ROLLBACK FAILED: see docs/runbooks/lite-host.md"
exit 1
