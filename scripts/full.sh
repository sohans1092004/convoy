#!/usr/bin/env bash
# Full AWS profile lifecycle (P6.E), run from the laptop with the convoy-aws session (make full-up / make full-down).
# Before anything is applied, `up` schedules the destroy at the maximum duration (MAX_MINUTES, default 60) through the
# in-account reaper (infra/aws/reaper, scripts/schedule-reap.sh), so a run ends even if this laptop is off.
#   full.sh up      <run-id> <images.env>   schedule the destroy, check Fargate headroom, apply (DB init first), wait for
#                                           services, seed demo dealerships, print the URL. TASKS_PER_SERVICE (default 2),
#                                           MAX_TASKS_PER_SERVICE (default 6); the P6 smoke run uses 1 and 1
#   full.sh down    <run-id>                scripts/reap.sh: destroy, verify the teardown, report (the same path as the reaper)
#   full.sh verify  <run-id>                tag inventory of the run must be empty
#   full.sh cost    <run-id> <hours>        Cost Explorer by the run tag if available, else the estimate
# Needs: AWS credentials (AWS_PROFILE=convoy-aws), terraform >= 1.10, jq; SHARED_JSON (infra/aws/shared outputs, + mail_from)
# and SECRETS_JSON (service secrets) in the environment.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/infra/aws/full"
cmd=$1 run=$2
REGION=${AWS_REGION:-ap-southeast-2}
account() { aws sts get-caller-identity --query Account --output text; }
tf_init() { terraform init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$(account)" >/dev/null; }

tfvars() { # images.env (+ third-party images resolved to digests) and the shared outputs -> run.auto.tfvars.json
  local images=$1
  python3 - "$images" "$run" <<'PY'
import json, os, re, subprocess, sys
images = {}
for line in open(sys.argv[1]):
    m = re.match(r'IMAGE_([A-Z_]+)=(\S+)', line.strip())
    if m: images[m.group(1).lower().replace('_', '-')] = m.group(2)
def digest(ref):  # third-party images are pinned by the digest their tag has today
    out = subprocess.run(['docker', 'buildx', 'imagetools', 'inspect', ref, '--format', '{{json .Manifest}}'], check=True, capture_output=True, text=True).stdout
    return ref.split(':')[0] + '@' + json.loads(out)['digest']
images['pgbouncer'] = digest('alpine:3.22.2')
images['postgres'] = digest('postgres:16.15-alpine')
images['adot'] = digest('public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3')
json.dump({'run_id': sys.argv[2], 'images': images, 'shared': json.loads(os.environ['SHARED_JSON']),
           'tasks_per_service': int(os.environ.get('TASKS_PER_SERVICE', '2')), 'max_tasks_per_service': int(os.environ.get('MAX_TASKS_PER_SERVICE', '6')),
           'secrets': json.loads(os.environ['SECRETS_JSON'])}, open('run.auto.tfvars.json', 'w'))
PY
}

one_off() { # task definition arn -> runs it in the private subnets, fails on a non-zero exit
  local arn
  arn=$(aws ecs run-task --region $REGION --cluster "$(terraform output -raw cluster)" --launch-type FARGATE --task-definition "$1" \
    --network-configuration "awsvpcConfiguration={subnets=[$(terraform output -raw private_subnets)],securityGroups=[$(terraform output -raw tasks_security_group)]}" \
    --query 'tasks[0].taskArn' --output text)
  aws ecs wait tasks-stopped --region $REGION --cluster "$(terraform output -raw cluster)" --tasks "$arn"
  code=$(aws ecs describe-tasks --region $REGION --cluster "$(terraform output -raw cluster)" --tasks "$arn" --query 'tasks[0].containers[0].exitCode' --output text)
  [ "$code" = 0 ] || { echo "one-off task $1 exited $code"; exit 1; }
}

case $cmd in
  up)
    # The destroy code is pinned to the commit this run is applied from: no uncommitted infra or script changes allowed.
    [ -z "$(git -C "$root" status --porcelain -- infra scripts)" ] || { echo "refused: commit infra/ and scripts/ first (the reaper runs that exact commit)"; exit 2; }
    sha=$(git -C "$root" rev-parse HEAD)
    (cd "$root" && scripts/schedule-reap.sh infra/aws/full "$run" "${MAX_MINUTES:-60}" "$sha") # first: the deadline holds whatever happens next
    tf_init
    tfvars "$3"
    # Headroom for the reaper: quota - Fargate vCPU already running in the account - this run's worst case >= 0.5
    quota=$(aws service-quotas get-service-quota --service-code fargate --quota-code L-3032A538 --query Quota.Value --output text)
    running=$(for c in $(aws ecs list-clusters --query 'clusterArns[]' --output text); do
        t=$(aws ecs list-tasks --cluster "$c" --desired-status RUNNING --query 'taskArns[]' --output text)
        [ -n "$t" ] && aws ecs describe-tasks --cluster "$c" --tasks $t --query 'tasks[?launchType==`FARGATE`].cpu' --output text
      done | tr '	' '
' | awk '{s+=$1} END {print s/1024+0}')
    worst=$(echo 'local.worst_case_vcpu' | terraform console -var services_running=true | tr -d '"')
    left=$(awk "BEGIN {print $quota - $running - $worst}")
    echo "Fargate on-demand vCPU: quota $quota, running $running, this run at most $worst -> $left left for the reaper"
    awk "BEGIN {exit !($left >= 0.5)}" || { echo "refused: less than 0.5 vCPU would be left for the reaper (lower TASKS_PER_SERVICE / MAX_TASKS_PER_SERVICE or raise the quota)"; exit 2; }
    terraform apply -input=false -auto-approve -var services_running=false
    one_off "$(terraform output -raw db_init_task)"
    terraform apply -input=false -auto-approve -var services_running=true
    aws ecs wait services-stable --region $REGION --cluster "$(terraform output -raw cluster)" --services $(terraform output -json services | jq -r '.[]')
    one_off "$(terraform output -raw seed_task)"
    echo "UP: $(terraform output -raw url)"
    ;;
  down)
    rm -f run.auto.tfvars.json
    (cd "$root" && scripts/reap.sh infra/aws/full "$run")
    aws scheduler delete-schedule --name "convoy-reap-$run" >/dev/null 2>&1 || true # no longer needed
    ;;
  verify)
    # The tagging API can list a resource for some minutes after it is deleted: retry for 15 minutes.
    for i in $(seq 1 30); do
      left=$(for r in $REGION; do aws resourcegroupstaggingapi get-resources --region $r \
        --tag-filters Key=project,Values=convoy Key=profile,Values=full Key=run,Values="$run" --query 'ResourceTagMappingList[].ResourceARN' --output text; done | tr '\t' '\n' | sed '/^$/d')
      [ -z "$left" ] && { echo "TEARDOWN VERIFIED: no resource tagged run=$run in $REGION"; exit 0; }
      echo "attempt $i: still listed:"; echo "$left"; sleep 30
    done
    echo "TEARDOWN NOT VERIFIED: resources above remain (docs/runbooks/full-profile.md, manual recovery)"; exit 1
    ;;
  cost)
    hours=$3
    start=$(date -u -d "-2 days" +%F) end=$(date -u -d "+1 day" +%F)
    if out=$(aws ce get-cost-and-usage --time-period Start="$start",End="$end" --granularity DAILY --metrics UnblendedCost \
          --filter "{\"Tags\":{\"Key\":\"run\",\"Values\":[\"$run\"]}}" --query 'ResultsByTime[].Total.UnblendedCost.Amount' --output text 2>&1); then
      echo "Cost Explorer (tag run=$run, can lag up to 24 h): $(echo "$out" | tr '\t' '\n' | awk '{s+=$1} END {printf "%.2f", s}') USD"
    else
      echo "Cost Explorer not available yet ($out)"
    fi
    echo "Estimate: $hours h x 1.49 USD/h idle (docs/cost/aws-cost-estimate.md §2, single-AZ RDS and one NAT are lower) = $(awk "BEGIN {printf \"%.2f\", $hours * 1.49}") USD"
    ;;
  *) echo "usage: full.sh up|down|verify|cost <run-id> ..."; exit 2 ;;
esac
