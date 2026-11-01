#!/usr/bin/env bash
# P7: run a k6 workload from the run's load generator (infra/aws/full/loadgen.tf) and bring the results home.
# The generator fetches the run's pre-provisioned sessions and the scripts at the run's commit, runs the setup and k6,
# and uploads k6's summary and per-second JSON to s3://<state>/full/<run>/results/ (deleted with the run), which this
# script copies to reports/p7/raw/<run>/ before anything is torn down.
#   scripts/p7-load.sh <run> w9 [RATE=1000] [HOLD=15m]
set -euo pipefail
run=$1 workload=$2 rate=${3:-1000} hold=${4:-15m}
root=$(cd "$(dirname "$0")/.." && pwd)
dir="$root/infra/aws/full"
account=$(aws sts get-caller-identity --query Account --output text)
state="s3://convoy-tfstate-$account/full/$run"
iid=$(cd "$dir" && terraform output -raw loadgen_instance); url=$(cd "$dir" && terraform output -raw url)
sha=$(git -C "$root" rev-parse HEAD)
[ -n "$iid" ] || { echo "no load generator in this run (LOADGEN_TYPE)"; exit 2; }
remote=$(cat <<SH
set -eux
mkdir -p /work && cd /work
curl -fsSL https://raw.githubusercontent.com/sohans1092004/convoy/$sha/tools/load/w9.k6.js -o w9.k6.js
curl -fsSL https://raw.githubusercontent.com/sohans1092004/convoy/$sha/tools/load/w9_setup.py -o w9_setup.py
aws s3 cp $state/load-sessions.json sessions.json --only-show-errors
python3 w9_setup.py $url sessions.json
ulimit -n 200000
k6 run -q -e BASE=$url -e RATE=$rate -e HOLD=$hold --summary-export summary.json --out json=points.json.gz w9.k6.js > k6.txt 2>&1 || echo "k6 exit \$?" >> k6.txt
top -bn1 | head -5 > host.txt
aws s3 cp summary.json $state/results/$workload-summary.json --only-show-errors
aws s3 cp points.json.gz $state/results/$workload-points.json.gz --only-show-errors
aws s3 cp k6.txt $state/results/$workload-k6.txt --only-show-errors
SH
)
cmd=$(aws ssm send-command --instance-ids "$iid" --document-name AWS-RunShellScript --timeout-seconds 10800 \
  --parameters "$(python3 -c 'import json,sys; print(json.dumps({"commands": [sys.argv[1]], "executionTimeout": ["10800"]}))' "$remote")" \
  --query Command.CommandId --output text)
echo "k6 $workload started on $iid (command $cmd), base $url, rate $rate, hold $hold"
until s=$(aws ssm get-command-invocation --command-id "$cmd" --instance-id "$iid" --query Status --output text 2>/dev/null) && [[ $s =~ ^(Success|Failed|Cancelled|TimedOut)$ ]]; do sleep 30; done
echo "generator finished: $s"
mkdir -p "$root/reports/p7/raw/$run"
aws s3 cp "$state/results/" "$root/reports/p7/raw/$run/" --recursive --only-show-errors
ls "$root/reports/p7/raw/$run"
