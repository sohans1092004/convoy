#!/usr/bin/env bash
# Journeys and the notification isolation test drive the local Razorpay fake's checkout ("Pay (test)"). With payment on
# real Razorpay test mode the deposit is never captured and the run burns ~5 minutes before timing out. Fail fast instead.
set -euo pipefail
cd "$(dirname "$0")/.."
want=http://razorpay-fake:9090
got=$(docker compose exec -T payment printenv RAZORPAY_BASE_URL 2>/dev/null | tr -d '\r') || got=""
if [ "$got" != "$want" ]; then
  echo "STOP: payment is not on the local Razorpay fake (RAZORPAY_BASE_URL='${got:-unreachable}', expected $want)." >&2
  echo "      Set RAZORPAY_BASE_URL=$want and RAZORPAY_KEY_ID=rzp_test_fake in .env, then: docker compose up -d payment web razorpay-fake" >&2
  exit 2
fi
