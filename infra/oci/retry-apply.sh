#!/usr/bin/env bash
# Always Free A1 capacity is often exhausted. Retry the apply in the SAME home region and shape, cycling through the
# region's availability domains, until it succeeds or another error appears. Never changes region or shape.
#   terraform plan -out tfplan (review it first)   then   ./retry-apply.sh
set -uo pipefail
cd "$(dirname "$0")"
ads=$(terraform console <<< 'length(data.oci_identity_availability_domains.ads.availability_domains)' 2>/dev/null || echo 1)
for attempt in $(seq 1 200); do
  ad=$(( (attempt - 1) % ads ))
  echo "attempt $attempt, availability domain index $ad"
  out=$(terraform apply -auto-approve -var "availability_domain_index=$ad" 2>&1); rc=$?
  echo "$out" | tail -5
  [ $rc -eq 0 ] && { echo "applied"; exit 0; }
  echo "$out" | grep -q "Out of host capacity" || { echo "stopped: not a capacity error"; exit $rc; }
  sleep $(( 60 + RANDOM % 60 ))
done
echo "gave up after 200 attempts"; exit 1
