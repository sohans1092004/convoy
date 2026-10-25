#!/usr/bin/env bash
# Create .env with random local secrets (keeps an existing .env). Razorpay keys default to the local fake.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo ".env exists; leaving it alone"; exit 0; fi
rnd() { head -c 24 /dev/urandom | base64 | tr -d '/+=' ; }
{
  echo "POSTGRES_PASSWORD=$(rnd)"
  for s in IDENTITY CATALOG POOL DEALER QUOTE ORDER PAYMENT; do echo "DB_PASSWORD_$s=$(rnd)"; done
  echo "IDENTITY_DATA_KEY=$(head -c 32 /dev/urandom | base64)"
  echo "CLIENT_SECRET_GATEWAY=$(rnd)"
  echo "CLIENT_SECRET_POOL=$(rnd)"
  echo "CLIENT_SECRET_REALTIME=$(rnd)"
  echo "DB_PASSWORD_REALTIME=$(rnd)"
  echo "RAZORPAY_BASE_URL=http://razorpay-fake:9090"
  echo "RAZORPAY_KEY_ID=rzp_test_fake"
  echo "RAZORPAY_KEY_SECRET=$(rnd)"
  echo "RAZORPAY_WEBHOOK_SECRET=$(rnd)"
  echo "DEMO_SEALED_ROUND_SECONDS=90"
  echo "DEMO_FINAL_ROUND_SECONDS=0"
  echo "DEMO_DECISION_SECONDS=60"
  echo "DEMO_CONFIRMATION_SECONDS=60"
  echo "POOL_MIN_MEMBERS=1"
} > .env
echo "wrote .env"
