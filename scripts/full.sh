#!/usr/bin/env bash
# Full AWS profile lifecycle (P6.E), run from the laptop with the convoy-aws session (make full-up / make full-down).
# Before anything is applied, `up` schedules the destroy at the maximum duration (MAX_MINUTES, default 60) through the
# in-account reaper (infra/aws/reaper, scripts/schedule-reap.sh), so a run ends even if this laptop is off.
#   full.sh up      <run-id> <images.env>   schedule the destroy, check Fargate headroom, apply (DB init first), wait for
#                                           services, seed demo dealerships, print the URL. TASKS_PER_SERVICE (default 2),
#                                           MAX_TASKS_PER_SERVICE (default 6); the P6 smoke run uses 1 and 1
#   full.sh canary-plan / canary <run-id> <images.env>   the reaper canary: a real slice (no RDS/ElastiCache, 0 tasks)
#                                           applied here and destroyed only by the scheduled reaper (CANARY_MINUTES, 20)
#   full.sh plan    <run-id> <images.env>   the same inputs and headroom check as up, then terraform plan only
#   full.sh down    <run-id>                scripts/reap.sh: destroy, verify the teardown, report (the same path as the reaper)
#   full.sh verify  <run-id>                tag inventory of the run must be empty
#   full.sh cost    <run-id> <hours>        Cost Explorer by the run tag if available, else the estimate
# Needs: AWS credentials (AWS_PROFILE=convoy-aws), terraform >= 1.10, python, docker; SHARED_JSON (infra/aws/shared outputs, + mail_from)
# and SECRETS_JSON (service secrets) in the environment.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/infra/aws/full"
cmd=$1 run=$2
REGION=${AWS_REGION:-ap-southeast-2}
py=$(command -v python3 || command -v python)
account() { aws sts get-caller-identity --query Account --output text; }
tf_init() { terraform init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$(account)" >/dev/null; }

tfvars() { # images.env (+ third-party images resolved to digests) and the shared outputs -> run.auto.tfvars.json
  local images=$1
  "$py" - "$images" "$run" <<'PY'
import json, os, re, subprocess, sys
images = {}
for line in open(sys.argv[1]):
    m = re.match(r'IMAGE_([A-Z_]+)=(\S+)', line.strip())
    if m: images[m.group(1).lower().replace('_', '-')] = m.group(2)
def digest(ref):  # third-party images are pinned by the digest their tag has today (3 tries: registry CDNs flake)
    import time
    for attempt in range(3):
        # --raw: only the manifest index (no blob downloads); its digest is the sha256 of exactly these bytes
        r = subprocess.run(['docker', 'buildx', 'imagetools', 'inspect', ref, '--raw'], capture_output=True)
        if r.returncode == 0:
            import hashlib
            return ref.split(':')[0] + '@sha256:' + hashlib.sha256(r.stdout).hexdigest()
        time.sleep(5)
    sys.exit(f'cannot resolve {ref}: {r.stderr.decode(errors="replace").strip()[:200]}')
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
  within aws ecs wait tasks-stopped --region $REGION --cluster "$(terraform output -raw cluster)" --tasks "$arn" || return 1
  code=$(aws ecs describe-tasks --region $REGION --cluster "$(terraform output -raw cluster)" --tasks "$arn" --query 'tasks[0].containers[0].exitCode' --output text)
  [ "$code" = 0 ] || { echo "one-off task $1 exited $code"; exit 1; }
}

headroom() { # the reaper must always be able to launch: quota - Fargate vCPU running - this run's worst case >= 0.5
  quota=$(aws service-quotas get-service-quota --service-code fargate --quota-code L-3032A538 --query Quota.Value --output text)
  running=$(for c in $(aws ecs list-clusters --query 'clusterArns[]' --output text); do
      t=$(aws ecs list-tasks --cluster "$c" --desired-status RUNNING --query 'taskArns[]' --output text)
      if [ -n "$t" ]; then aws ecs describe-tasks --cluster "$c" --tasks $t --query 'tasks[?launchType==`FARGATE`].cpu' --output text; fi
    done | tr '	' '
' | awk '{s+=$1} END {print s/1024+0}')
  worst=$(echo 'local.worst_case_vcpu' | terraform console -var services_running=true | tr -d '"\r' | grep -E '^[0-9.]+$' | tail -1)
  [ -n "$worst" ] || { echo "refused: could not compute this run's worst-case vCPU"; exit 2; }
  left=$(awk "BEGIN {print $quota - $running - $worst}")
  echo "Fargate on-demand vCPU: quota $quota, running $running, this run at most $worst -> $left left for the reaper"
  awk "BEGIN {exit !($left >= 0.5)}" || { echo "refused: less than 0.5 vCPU would be left for the reaper (lower TASKS_PER_SERVICE / MAX_TASKS_PER_SERVICE or raise the quota)"; exit 2; }
}

case $cmd in
  plan)
    tf_init
    tfvars "$3"
    headroom
    terraform plan -input=false -no-color -var services_running=true | tee plan.txt | grep -E "^Plan:|Error|will be|must be"
    ;;
  canary-plan)
    tf_init
    tfvars "$3"
    headroom
    terraform plan -input=false -no-color -var canary=true -var services_running=false | tee plan.txt | grep -E "^Plan:|Error|will be|must be"
    ;;
  canary)
    started=$(date +%s)
    # Reaper canary (P6.E): a real slice of this config with the real variables (VPC, NAT, ALB + WAF + certificate,
    # ECS cluster, services at 0 tasks, IAM, secret; no RDS/ElastiCache), destroyed ONLY by the scheduled reaper.
    # PASS = the reaper task exits 0, logs TEARDOWN VERIFIED, and nothing of the run is live afterwards.
    [ -z "$(git -C "$root" status --porcelain -- infra scripts)" ] || { echo "refused: commit infra/ and scripts/ first (the reaper runs that exact commit)"; exit 2; }
    sha=$(git -C "$root" rev-parse HEAD)
    account=$(account) role="arn:aws:iam::$account:role/convoy-reaper"
    sim() { aws iam simulate-principal-policy --policy-source-arn "$role" --action-names elasticloadbalancing:DeleteLoadBalancer \
      --resource-arns "arn:aws:elasticloadbalancing:$REGION:$account:loadbalancer/app/x/y" \
      --context-entries ContextKeyName=aws:RequestedRegion,ContextKeyValues="$REGION",ContextKeyType=string "$@" --query 'EvaluationResults[0].EvalDecision' --output text; }
    full=$(sim ContextKeyName=aws:ResourceTag/project,ContextKeyValues=convoy,ContextKeyType=string ContextKeyName=aws:ResourceTag/profile,ContextKeyValues=full,ContextKeyType=string)
    lite=$(sim ContextKeyName=aws:ResourceTag/project,ContextKeyValues=convoy,ContextKeyType=string ContextKeyName=aws:ResourceTag/profile,ContextKeyValues=lite,ContextKeyType=string)
    echo "scope: delete convoy/full load balancer: $full; convoy/lite: $lite"
    [ "$full" = allowed ] && [ "$lite" != allowed ] || { echo "CANARY FAILED: reaper scope"; exit 1; }
    (cd "$root" && scripts/schedule-reap.sh infra/aws/full "$run" "${CANARY_MINUTES:-20}" "$sha")
    tf_init
    tfvars "$3"
    headroom
    terraform apply -input=false -auto-approve -no-color -var canary=true -var services_running=false | grep -E "Apply complete|Error" || true
    rm -f run.auto.tfvars.json
    echo "applied (state: $(terraform state list 2>/dev/null | wc -l) resources); waiting for the reaper, which alone destroys it"
    until task=$(aws ecs list-tasks --cluster convoy-reaper --started-by "$run" --desired-status STOPPED --query 'taskArns[0]' --output text) && [ "$task" != None ]; do
      [ $(( $(date +%s) - started )) -gt $(( (${CANARY_MINUTES:-20} + 45) * 60 )) ] && { echo "CANARY FAILED: no finished reaper task"; exit 1; }
      sleep 30
    done
    code=$(aws ecs describe-tasks --cluster convoy-reaper --tasks "$task" --query 'tasks[0].containers[0].exitCode' --output text)
    verdict=$(MSYS_NO_PATHCONV=1 aws logs filter-log-events --log-group-name /convoy/reaper --log-stream-names "reaper/reaper/${task##*/}" \
      --filter-pattern '"TEARDOWN"' --query 'events[-1].message' --output text)
    pinned=$(MSYS_NO_PATHCONV=1 aws logs filter-log-events --log-group-name /convoy/reaper --log-stream-names "reaper/reaper/${task##*/}" \
      --filter-pattern '"pinned at"' --query 'events[0].message' --output text)
    live="vpc=$(aws ec2 describe-vpcs --filters Name=tag:run,Values="$run" --query 'length(Vpcs)' --output text)"
    live="$live alb=$(aws elbv2 describe-load-balancers --query "length(LoadBalancers[?contains(LoadBalancerName,'$run')])" --output text)"
    live="$live nat=$(aws ec2 describe-nat-gateways --filter Name=tag:run,Values="$run" Name=state,Values=pending,available,deleting --query 'length(NatGateways)' --output text)"
    live="$live cluster=$(aws ecs describe-clusters --clusters "convoy-full-$run" --query "length(clusters[?status=='ACTIVE'])" --output text)"
    echo "reaper task exit $code; $pinned (expected $sha); $verdict; live: $live"
    [ "$code" = 0 ] && echo "$verdict" | grep -q "TEARDOWN VERIFIED" && echo "$pinned" | grep -q "$sha" && [ "$live" = "vpc=0 alb=0 nat=0 cluster=0" ] \
      && echo "CANARY PASSED" || { echo "CANARY FAILED"; exit 1; }
    ;;
  up)
    started=$(date +%s)
    # The destroy code is pinned to the commit this run is applied from: no uncommitted infra or script changes allowed.
    [ -z "$(git -C "$root" status --porcelain -- infra scripts)" ] || { echo "refused: commit infra/ and scripts/ first (the reaper runs that exact commit)"; exit 2; }
    sha=$(git -C "$root" rev-parse HEAD)
    (cd "$root" && scripts/schedule-reap.sh infra/aws/full "$run" "${MAX_MINUTES:-60}" "$sha") # first: the deadline holds whatever happens next
    tf_init
    tfvars "$3"
    headroom
    # Creation must finish by minute CREATE_MINUTES (35) so the destroy ends well inside the 60-minute limit: past it,
    # the running step is interrupted (SIGINT: terraform finishes in-flight calls, saves state, unlocks) and down runs.
    deadline=$(( started + ${CREATE_MINUTES:-35} * 60 ))
    within() { local left=$(( deadline - $(date +%s) )); [ "$left" -gt 0 ] || return 124; timeout -s INT -k 180 "$left" "$@"; }
    abort() { echo "creation not finished by minute ${CREATE_MINUTES:-35} (or a step failed): destroying now"; bash "$root/scripts/full.sh" down "$run"; exit 3; }
    within terraform apply -input=false -auto-approve -var services_running=false || abort
    one_off "$(terraform output -raw db_init_task)" || abort
    within terraform apply -input=false -auto-approve -var services_running=true || abort
    # services-stable takes at most 10 services per call
    cluster=$(terraform output -raw cluster)
    for chunk in $(terraform output -json services | "$py" -c 'import json,sys; s=json.load(sys.stdin); print(" ".join(",".join(s[i:i+10]) for i in range(0, len(s), 10)))'); do
      within aws ecs wait services-stable --region $REGION --cluster "$cluster" --services ${chunk//,/ } || abort
    done
    one_off "$(terraform output -raw seed_task)" || abort
    echo "UP after $(( ($(date +%s) - started) / 60 )) min: $(terraform output -raw url)"
    ;;
  down)
    # First the run's reaper schedule (it must not fire while this destroy runs), then the same destroy path it uses.
    aws scheduler delete-schedule --name "convoy-reap-$run" >/dev/null 2>&1 && echo "schedule convoy-reap-$run deleted" || echo "no schedule convoy-reap-$run"
    rm -f run.auto.tfvars.json
    (cd "$root" && scripts/reap.sh infra/aws/full "$run")
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
