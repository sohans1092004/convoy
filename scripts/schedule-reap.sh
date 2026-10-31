#!/usr/bin/env bash
# Schedule the destroy of a full-profile run (or the reaper canary) at its deadline: a one-time EventBridge Scheduler
# schedule starts the convoy-full-reaper CodeBuild project, which runs scripts/reap.sh at the given commit. The schedule
# deletes itself after it fires. Created before anything is applied, so even a failed or abandoned run is destroyed.
#   schedule-reap.sh <terraform dir> <run-id> <minutes from now> [commit sha, default HEAD]
set -euo pipefail
cd "$(dirname "$0")/.."
dir=$1 run=$2 minutes=$3 sha=${4:-$(git rev-parse HEAD)}
region=${AWS_REGION:-ap-southeast-2}
account=$(aws sts get-caller-identity --query Account --output text)
git cat-file -e "$sha^{commit}" && git branch -r --contains "$sha" | grep -q . || { echo "refused: $sha is not pushed (the reaper clones it from GitHub)"; exit 2; }
at=$(date -u -d "+$minutes minutes" +%Y-%m-%dT%H:%M:%S)
input=$(printf '{"ProjectName":"convoy-full-reaper","EnvironmentVariablesOverride":[{"Name":"TARGET_DIR","Value":"%s","Type":"PLAINTEXT"},{"Name":"RUN_ID","Value":"%s","Type":"PLAINTEXT"},{"Name":"SOURCE_SHA","Value":"%s","Type":"PLAINTEXT"}]}' "$dir" "$run" "$sha")
target=$("$(command -v python3 || command -v python)" -c 'import json,sys; print(json.dumps({"Arn": "arn:aws:scheduler:::aws-sdk:codebuild:startBuild", "RoleArn": sys.argv[1], "Input": sys.argv[2]}))' \
  "arn:aws:iam::$account:role/convoy-reaper-scheduler" "$input")
aws scheduler create-schedule --region "$region" --name "convoy-reap-$run" --schedule-expression "at($at)" \
  --schedule-expression-timezone UTC --flexible-time-window Mode=OFF --action-after-completion DELETE --target "$target" >/dev/null
echo "destroy of $dir run $run scheduled at $at UTC (convoy-reap-$run, commit ${sha:0:12})"
