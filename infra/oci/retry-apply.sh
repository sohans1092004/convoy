#!/usr/bin/env bash
# Always Free A1 capacity is often exhausted ("Out of host capacity"). Retry in the SAME home region and shape, cycling
# through the region's availability domains, until the instance exists or another error appears. Never changes region
# or shape. Each attempt re-plans and applies only if the plan is exactly the instance: 1 to add, 0 to change,
# 0 to destroy (anything else stops the loop). A failed launch costs nothing.
#   ./retry-apply.sh [max attempts, default 288]      (every 5-10 min: about a day)
set -uo pipefail
cd "$(dirname "$0")"
max=${1:-288}
ads=$(terraform output -raw ad_count 2>/dev/null || echo 1)
for attempt in $(seq 1 "$max"); do
  ad=$(( (attempt - 1) % ads ))
  terraform plan -input=false -no-color -var "availability_domain_index=$ad" -out retry.tfplan > retry-plan.txt 2>&1 || { tail -5 retry-plan.txt; echo "stopped: plan failed"; exit 1; }
  if ! grep -q "Plan: 1 to add, 0 to change, 0 to destroy" retry-plan.txt || ! grep -q "oci_core_instance.lite will be created" retry-plan.txt; then
    grep -E "^Plan:|No changes" retry-plan.txt; echo "stopped: the plan is not exactly the instance"; exit 1
  fi
  out=$(terraform apply -input=false -no-color retry.tfplan 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "$(date -u +%FT%TZ) attempt $attempt: instance created"; terraform output -no-color; exit 0; fi
  echo "$out" | grep -q "Out of host capacity" || { echo "$out" | tail -5; echo "stopped: not a capacity error"; exit $rc; }
  echo "$(date -u +%FT%TZ) attempt $attempt (AD index $ad): out of host capacity"
  sleep $(( 300 + RANDOM % 300 ))
done
echo "gave up after $max attempts"; exit 1
