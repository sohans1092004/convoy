#!/usr/bin/env bash
# Deploy a main commit to the lite host from the owner's laptop (docs/runbooks/lite-host.md):
#   aws login --profile convoy-aws && scripts/deploy-lite.sh <sha>      (LITE_INSTANCE, LITE_SITE, AWS_PROFILE override the defaults)
# Downloads that commit's images.env from its successful `images` run and sends it through the convoy-deploy SSM document
# (deploy-ssh.sh -> deploy.sh: backup, one service at a time, smoke test, automatic rollback). Then checks /version.
set -euo pipefail
sha=$(git rev-parse "${1:?usage: deploy-lite.sh <sha>}")
instance=${LITE_INSTANCE:-i-0829475b795c8667e} site=${LITE_SITE:-convoy-main.duckdns.org}
aws_() { aws --profile "${AWS_PROFILE:-convoy-aws}" --region ap-southeast-2 "$@" | tr -d '\r'; } # aws.exe writes CRLF on Windows
run=$(gh run list --workflow images.yml --branch main --status success --limit 50 --json databaseId,headSha \
  --jq ".[] | select(.headSha==\"$sha\") | .databaseId" | head -1)
[ -n "$run" ] || { echo "no successful images run on main for $sha" >&2; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -n "images-$sha" -D "$tmp"
images=$(base64 -w0 "$tmp/images.env")
command=$(aws_ ssm send-command --document-name convoy-deploy --instance-ids "$instance" \
  --parameters "{\"sha\":[\"$sha\"],\"images\":[\"$images\"]}" --comment "deploy $sha" --query Command.CommandId --output text) \
  || { echo "could not start the deploy. AWS session expired? Run: aws login --profile ${AWS_PROFILE:-convoy-aws}" >&2; exit 1; }
echo "deploying $sha (SSM command $command)"
status=Pending
for _ in $(seq 1 380); do # the document's own limit is 30 minutes
  sleep 5
  status=$(aws_ ssm get-command-invocation --command-id "$command" --instance-id "$instance" --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in Success|Failed|Cancelled|TimedOut|DeliveryTimedOut) break ;; esac
done
aws_ ssm get-command-invocation --command-id "$command" --instance-id "$instance" --query StandardOutputContent --output text | tail -30
aws_ ssm get-command-invocation --command-id "$command" --instance-id "$instance" --query StandardErrorContent --output text | tail -10
[ "$status" = Success ] || { echo "deploy $status (deploy.sh rolls back by itself; SSM command $command)" >&2; exit 1; }
curl -fsS --max-time 15 "https://$site/version"; echo
