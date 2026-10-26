#!/usr/bin/env bash
# Create .env with random local secrets. An existing .env is kept; only keys added since it was written are appended
# (never printed). Razorpay keys default to the local fake.
set -euo pipefail
cd "$(dirname "$0")/.."
rnd() { head -c 24 /dev/urandom | base64 | tr -d '/+=' ; }
fresh=0
[ -f .env ] || { : > .env; fresh=1; }
add() { grep -q "^$1=" .env || echo "$1=$2" >> .env; }
add POSTGRES_PASSWORD "$(rnd)"
for s in IDENTITY CATALOG POOL DEALER QUOTE ORDER PAYMENT; do add "DB_PASSWORD_$s" "$(rnd)"; done
add IDENTITY_DATA_KEY "$(head -c 32 /dev/urandom | base64)"
add CLIENT_SECRET_GATEWAY "$(rnd)"
add CLIENT_SECRET_POOL "$(rnd)"
add CLIENT_SECRET_REALTIME "$(rnd)"
add CLIENT_SECRET_ORDER "$(rnd)" # P4.B: order reads a buyer's contact from identity after a booking is issued
add DB_PASSWORD_REALTIME "$(rnd)"
add RAZORPAY_BASE_URL "http://razorpay-fake:9090"
add RAZORPAY_KEY_ID "rzp_test_fake"
add RAZORPAY_KEY_SECRET "$(rnd)"
add RAZORPAY_WEBHOOK_SECRET "$(rnd)"
add DEMO_SEALED_ROUND_SECONDS 90
add DEMO_FINAL_ROUND_SECONDS 20 # long enough to revise in the final round (P3.F)
add DEMO_DECISION_SECONDS 60
add DEMO_CONFIRMATION_SECONDS 60
add DEMO_REROUND_CHOICE_SECONDS 120 # AMENDMENT-002 choice window, 48 h in production
add POOL_MIN_MEMBERS 1
add DEMO true
[ "$fresh" = 1 ] && echo "wrote .env" || echo ".env exists; added any missing keys"
