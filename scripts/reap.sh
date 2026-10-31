#!/usr/bin/env bash
# The one destroy path for full-profile runs (P6.E), used by `make full-down` on the laptop and by the convoy-full-reaper
# CodeBuild project at a run's deadline (EventBridge Scheduler, scripts/schedule-reap.sh):
#   1. terraform destroy of <dir> (state in s3://convoy-tfstate-<account>/<dir's backend key>)
#   2. teardown verification: no resource tagged project=convoy, profile=full, run=<run> may remain (retried 15 min)
#   3. report: the result (and the run's cost so far) to the convoy-alerts topic (email) and the build log
#   reap.sh <terraform dir> <run-id>     e.g. reap.sh infra/aws/full r202610311200
# Exit 0 only when the destroy succeeded and the verification found nothing.
set -uo pipefail
cd "$(dirname "$0")/.."
dir=$1 run=$2
region=${AWS_REGION:-ap-southeast-2}
account=$(aws sts get-caller-identity --query Account --output text)
log() { echo "$(date -u +%FT%TZ) $*"; }

# Destroy needs every variable set; the values do not matter (nothing is created or updated), only the run id does.
if grep -q 'variable "images"' "$dir"/*.tf; then
  echo "{\"run_id\":\"$run\",\"images\":{},\"secrets\":{\"POSTGRES_PASSWORD\":\"destroy-only\"},\"shared\":{\"smtp_host\":\"x\",\"smtp_username\":\"x\",\"mail_from\":\"x\",\"export_bucket\":\"x\",\"evidence_bucket\":\"x\"}}" > "$dir/reap.auto.tfvars.json"
else
  echo "{\"run_id\":\"$run\"}" > "$dir/reap.auto.tfvars.json"
fi

log "destroy $dir (run $run)"
terraform -chdir="$dir" init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$account" >/dev/null &&
  terraform -chdir="$dir" destroy -input=false -auto-approve -no-color
destroyed=$?
rm -f "$dir/reap.auto.tfvars.json"

verified=1 left=""
for i in $(seq 1 30); do
  left=$(aws resourcegroupstaggingapi get-resources --region "$region" \
    --tag-filters Key=project,Values=convoy Key=profile,Values=full Key=run,Values="$run" \
    --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | sed '/^$/d')
  [ -z "$left" ] && { verified=0; break; }
  log "verification attempt $i: still listed: $(echo "$left" | tr '\n' ' ')"
  sleep 30
done

cost=$(aws ce get-cost-and-usage --time-period Start="$(date -u -d '-2 days' +%F)",End="$(date -u -d '+1 day' +%F)" \
  --granularity DAILY --metrics UnblendedCost --filter "{\"Tags\":{\"Key\":\"run\",\"Values\":[\"$run\"]}}" \
  --query 'ResultsByTime[].Total.UnblendedCost.Amount' --output text 2>/dev/null | tr '\t' '\n' | awk '{s+=$1} END {printf "%.2f USD (Cost Explorer lags up to 24 h)", s}')

if [ $destroyed = 0 ] && [ $verified = 0 ]; then
  status="TEARDOWN VERIFIED"; detail="destroy ok; no resource tagged run=$run remains in $region"
else
  status="TEARDOWN FAILED"; detail="destroy exit $destroyed; still tagged run=$run: ${left:-none}. Manual recovery: docs/runbooks/full-profile.md"
fi
msg="$status: $dir run $run. $detail. Cost so far: ${cost:-unavailable}. Build: ${CODEBUILD_BUILD_ID:-laptop}."
log "$msg"
aws sns publish --region "$region" --topic-arn "arn:aws:sns:$region:$account:convoy-alerts" \
  --subject "Convoy $status ($run)" --message "$msg" >/dev/null || log "report not sent (convoy-alerts unreachable)"
[ "$status" = "TEARDOWN VERIFIED" ]
