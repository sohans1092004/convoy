#!/usr/bin/env bash
# The deploy gate. On EC2 the convoy-deploy SSM document sets SSH_ORIGINAL_COMMAND and runs it as the deploy user; a host
# reached over SSH (Oracle) runs it as the deploy key's forced command in authorized_keys, set by bootstrap.sh.
# Accepts exactly "deploy <40-hex sha>" with images.env on stdin, and only image references by digest from this repo.
set -euo pipefail
read -r verb sha extra <<< "${SSH_ORIGINAL_COMMAND:-}"
[[ "$verb" == deploy && "$sha" =~ ^[0-9a-f]{40}$ && -z "${extra:-}" ]] || { echo "refused: only 'deploy <sha>'" >&2; exit 2; }
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
head -c 8192 > "$tmp"
grep -vqE '^IMAGE_[A-Z_]+=ghcr\.io/sohans1092004/convoy/convoy-[a-z-]+@sha256:[0-9a-f]{64}$' "$tmp" && { echo "refused: images.env must be digests from ghcr.io/sohans1092004" >&2; exit 2; }
exec /opt/convoy/src/deploy/lite/deploy.sh "$sha" "$tmp"
