#!/usr/bin/env bash
# Full AWS profile lifecycle (P6.E), run from the laptop with the convoy-aws session (make full-up / make full-down).
# The scheduled destroy at the maximum duration is pending a decision (docs/runbooks/full-profile.md).
#   full.sh up      <run-id> <images.env>   apply (DB init first), wait for services, seed demo dealerships, print the URL
#   full.sh down    <run-id>                destroy, then verify the teardown and report the cost
#   full.sh verify  <run-id>                tag inventory of the run must be empty
#   full.sh cost    <run-id> <hours>        Cost Explorer by the run tag if available, else the estimate
# Needs: AWS credentials (AWS_PROFILE=convoy-aws), terraform >= 1.10, jq; SHARED_JSON (infra/aws/shared outputs, + mail_from)
# and SECRETS_JSON (service secrets) in the environment.
set -euo pipefail
cd "$(dirname "$0")/../infra/aws/full"
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
    tf_init
    tfvars "$3"
    terraform apply -input=false -auto-approve -var services_running=false
    one_off "$(terraform output -raw db_init_task)"
    terraform apply -input=false -auto-approve -var services_running=true
    aws ecs wait services-stable --region $REGION --cluster "$(terraform output -raw cluster)" --services $(terraform output -json services | jq -r '.[]')
    one_off "$(terraform output -raw seed_task)"
    echo "UP: $(terraform output -raw url)"
    ;;
  down)
    tf_init
    [ -f run.auto.tfvars.json ] || echo "{\"run_id\":\"$run\",\"images\":{},\"shared\":$SHARED_JSON,\"secrets\":$SECRETS_JSON}" > run.auto.tfvars.json
    terraform destroy -input=false -auto-approve
    "$0" verify "$run"
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
