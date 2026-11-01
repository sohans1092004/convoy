#!/usr/bin/env bash
# Deploy a main commit to the lite host from the owner's laptop (docs/runbooks/lite-host.md):
#   scripts/deploy-lite.sh <sha>      (LITE_HOST, LITE_KEY override the defaults)
# Downloads that commit's images.env from its successful `images` run and sends it to the host's forced command
# (deploy-ssh.sh -> deploy.sh: backup, one service at a time, smoke test, automatic rollback). Then checks /version.
set -euo pipefail
sha=$(git rev-parse "${1:?usage: deploy-lite.sh <sha>}")
host=${LITE_HOST:-52.64.115.0} key=${LITE_KEY:-$HOME/.ssh/convoy-deploy} site=${LITE_SITE:-convoy-main.duckdns.org}
run=$(gh run list --workflow images.yml --branch main --status success --limit 50 --json databaseId,headSha \
  --jq ".[] | select(.headSha==\"$sha\") | .databaseId" | head -1)
[ -n "$run" ] || { echo "no successful images run on main for $sha" >&2; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -n "images-$sha" -D "$tmp"
ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 "deploy@$host" "deploy $sha" < "$tmp/images.env" \
  || { echo "deploy failed (deploy.sh rolls back by itself). SSH timeout? Your IP may have changed: see the runbook." >&2; exit 1; }
curl -fsS --max-time 15 "https://$site/version"; echo
