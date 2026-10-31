#!/usr/bin/env bash
# End-to-end test of the scheduled destroy (P6.E), run once before a full run relies on it:
#   1. scope: the reaper role may delete a convoy+full-tagged queue, and may not delete one tagged convoy+lite or untagged
#      (IAM policy simulation, nothing created)
#   2. apply the canary (one empty SQS queue tagged project=convoy, profile=full, run=<canary id>) from this laptop
#   3. schedule the reaper 3 minutes ahead, wait for the CodeBuild build, check: build succeeded, its log says
#      TEARDOWN VERIFIED, the queue is gone, the schedule deleted itself
#   AWS_PROFILE=convoy-aws scripts/reaper-canary.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export AWS_REGION=${AWS_REGION:-ap-southeast-2}
account=$(aws sts get-caller-identity --query Account --output text)
run="canary$(date -u +%Y%m%d%H%M)"
role="arn:aws:iam::$account:role/convoy-reaper"
# terraform from PATH, else the official image with this session's (short-lived) credentials, refreshed on every call
if command -v terraform >/dev/null; then tf() { terraform "$@"; }; else
  tf() {
    eval "$(aws configure export-credentials --format env)"
    MSYS_NO_PATHCONV=1 docker run --rm -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN -e AWS_REGION       -v "$(cygpath -w "$PWD" 2>/dev/null || pwd):/w" -w /w hashicorp/terraform:1.10.5 "$@"
  }
fi
q="arn:aws:sqs:$AWS_REGION:$account:convoy-reaper-canary-$run"

echo "== 1. scope (policy simulation)"
# the region is part of the context: the organisation SCP denies everything outside it
sim() { aws iam simulate-principal-policy --policy-source-arn "$role" --action-names sqs:DeleteQueue --resource-arns "$q" \
  --context-entries ContextKeyName=aws:RequestedRegion,ContextKeyValues="$AWS_REGION",ContextKeyType=string "$@" --query 'EvaluationResults[0].EvalDecision' --output text; }
full=$(sim ContextKeyName=aws:ResourceTag/project,ContextKeyValues=convoy,ContextKeyType=string ContextKeyName=aws:ResourceTag/profile,ContextKeyValues=full,ContextKeyType=string)
lite=$(sim ContextKeyName=aws:ResourceTag/project,ContextKeyValues=convoy,ContextKeyType=string ContextKeyName=aws:ResourceTag/profile,ContextKeyValues=lite,ContextKeyType=string)
other=$(sim ContextKeyName=aws:ResourceTag/project,ContextKeyValues=concord,ContextKeyType=string)
echo "delete convoy/full: $full; convoy/lite: $lite; concord: $other"
[ "$full" = allowed ] && [ "$lite" != allowed ] && [ "$other" != allowed ] || { echo "SCOPE CHECK FAILED"; exit 1; }

echo "== 2. canary $run"
tf -chdir=infra/aws/reaper/canary init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$account" >/dev/null
tf -chdir=infra/aws/reaper/canary apply -input=false -auto-approve -var "run_id=$run" -no-color | tail -2
aws sqs get-queue-url --queue-name "convoy-reaper-canary-$run" --query QueueUrl --output text

echo "== 3. scheduled destroy"
[ -z "$(git status --porcelain -- infra scripts)" ] || { echo "refused: commit and push infra/ and scripts/ first"; exit 2; }
sha=$(git rev-parse HEAD)
scripts/schedule-reap.sh infra/aws/reaper/canary "$run" 3 "$sha"
until build=$(aws codebuild list-builds-for-project --project-name convoy-full-reaper --query 'ids[0]' --output text) &&
      [ "$build" != None ] && aws codebuild batch-get-builds --ids "$build" --query 'builds[0].environment.environmentVariables[?name==`RUN_ID`].value' --output text | grep -qx "$run"; do
  sleep 20
done
echo "build $build started"
until st=$(aws codebuild batch-get-builds --ids "$build" --query 'builds[0].buildStatus' --output text) && [ "$st" != IN_PROGRESS ]; do sleep 20; done
stream=$(aws codebuild batch-get-builds --ids "$build" --query 'builds[0].logs.streamName' --output text)
verdict=$(aws logs filter-log-events --log-group-name /convoy/reaper --log-stream-names "$stream" --filter-pattern '"TEARDOWN"' \
  --query 'events[-1].message' --output text)
gone=$(aws sqs get-queue-url --queue-name "convoy-reaper-canary-$run" >/dev/null 2>&1 && echo no || echo yes)
sched=$(aws scheduler get-schedule --name "convoy-reap-$run" >/dev/null 2>&1 && echo still-there || echo deleted)
pinned=$(aws logs filter-log-events --log-group-name /convoy/reaper --log-stream-names "$stream" --filter-pattern '"reaper code pinned at"'   --query 'events[0].message' --output text)
echo "build: $st; $pinned (expected $sha); report: $verdict; queue gone: $gone; schedule: $sched"
[ "$st" = SUCCEEDED ] && [ "$gone" = yes ] && echo "$pinned" | grep -q "$sha" && echo "$verdict" | grep -q "TEARDOWN VERIFIED" && echo "CANARY PASSED" || { echo "CANARY FAILED"; exit 1; }
