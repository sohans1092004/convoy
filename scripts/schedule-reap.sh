#!/usr/bin/env bash
# Schedule the destroy of a full-profile run (or the reaper canary) at its deadline: a one-time EventBridge Scheduler
# schedule runs the convoy-reaper Fargate task (infra/aws/reaper), which runs scripts/reap.sh at the given commit. The
# schedule deletes itself after it fires; the task is started with StartedBy = <run-id>. Created before anything is applied, so even a failed or abandoned run is destroyed.
#   schedule-reap.sh <terraform dir> <run-id> <minutes from now> <full commit sha the run was applied from>
# The reaper checks out exactly that commit (never a branch), so the destroy code cannot change under a running run.
set -euo pipefail
cd "$(dirname "$0")/.."
dir=$1 run=$2 minutes=$3 sha=$4
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "refused: give the full 40-character commit sha"; exit 2; }
region=${AWS_REGION:-ap-southeast-2}
account=$(aws sts get-caller-identity --query Account --output text)
git cat-file -e "$sha^{commit}" && git branch -r --contains "$sha" | grep -q . || { echo "refused: $sha is not pushed (the reaper clones it from GitHub)"; exit 2; }
at=$(date -u -d "+$minutes minutes" +%Y-%m-%dT%H:%M:%S)
subnet=$(aws ec2 describe-subnets --region "$region" --filters Name=tag:Name,Values=convoy-reaper Name=tag:project,Values=convoy --query 'Subnets[0].SubnetId' --output text)
sg=$(aws ec2 describe-security-groups --region "$region" --filters Name=group-name,Values=convoy-reaper --query 'SecurityGroups[0].GroupId' --output text)
[[ "$subnet" == subnet-* && "$sg" == sg-* ]] || { echo "refused: the reaper is not applied (infra/aws/reaper)"; exit 2; }
py=$(command -v python3 || command -v python)
# ECS RunTask request in the Scheduler universal target's (PascalCase) member names
target=$("$py" -c '
import json, sys
role, subnet, sg, dir_, run, sha = sys.argv[1:]
env = [{"Name": k, "Value": v} for k, v in (("TARGET_DIR", dir_), ("RUN_ID", run), ("SOURCE_SHA", sha))]
req = {"Cluster": "convoy-reaper", "TaskDefinition": "convoy-reaper", "LaunchType": "FARGATE", "StartedBy": run[:36],
       "NetworkConfiguration": {"AwsvpcConfiguration": {"Subnets": [subnet], "SecurityGroups": [sg], "AssignPublicIp": "ENABLED"}},
       "Overrides": {"ContainerOverrides": [{"Name": "reaper", "Environment": env}]}}
print(json.dumps({"Arn": "arn:aws:scheduler:::aws-sdk:ecs:runTask", "RoleArn": role, "Input": json.dumps(req),
                  "RetryPolicy": {"MaximumRetryAttempts": 10, "MaximumEventAgeInSeconds": 3600}}))
' "arn:aws:iam::$account:role/convoy-reaper-scheduler" "$subnet" "$sg" "$dir" "$run" "$sha")
aws scheduler create-schedule --region "$region" --name "convoy-reap-$run" --schedule-expression "at($at)"   --schedule-expression-timezone UTC --flexible-time-window Mode=OFF --action-after-completion DELETE --target "$target" >/dev/null
echo "destroy of $dir run $run scheduled at $at UTC (convoy-reap-$run, commit ${sha:0:12})"
