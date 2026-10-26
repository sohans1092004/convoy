#!/usr/bin/env bash
# Reset the local demo stack's transactional data and re-seed. Local docker compose stack only; refuses unless DEMO=true.
#   bash scripts/reset-demo.sh
# Clears (every row belongs to demo users: only a DEMO stack is accepted):
#   pool, quote, order, payment, notification, realtime: all tables (pools, memberships, rounds, quotes, orders, bookings,
#     disputes, payments, refunds, ledger, notifications, plus each service's outbox / inbox / version bookkeeping)
#   dealer: reviews and reliability (they derive from bookings)
#   SQS: every convoy-* queue is purged (in-flight events would point at deleted rows)
# Keeps: users, sessions, passkeys, dealerships and staff, catalogue, saved cars, buyer preferences, order's voucher keys.
# Then restarts the services (identity and dealer re-apply their DEMO seeds; the Razorpay fake forgets its orders).
# The ledger's row triggers forbid UPDATE/DELETE; TRUNCATE is the deliberate whole-ledger reset of a demo database.
set -euo pipefail
cd "$(dirname "$0")/.."
demo=${DEMO:-$(grep -E '^DEMO=' .env 2>/dev/null | tail -1 | cut -d= -f2- || true)}
[ "$demo" = "true" ] || { echo "REFUSED: DEMO is not true (.env / environment). This tool only resets a local demo stack." >&2; exit 2; }
endpoint=$(docker compose exec -T payment printenv AWS_ENDPOINT 2>/dev/null | tr -d '\r' || true)
[ "$endpoint" = "http://localstack:4566" ] || { echo "REFUSED: payment is not on the local stack (AWS_ENDPOINT='$endpoint')." >&2; exit 2; }

apps="pool quote order payment notification realtime-1 dealer identity gateway catalog razorpay-fake web"
echo "stopping services"
docker compose stop $apps >/dev/null

echo "clearing demo data"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U convoy_admin -d convoy -q <<'SQL'
do $$
declare t text;
begin
  select string_agg(format('%I.%I', schemaname, tablename), ', ') into t from pg_tables
  where (schemaname in ('pool', 'quote', 'order', 'payment', 'notification', 'realtime')
         and tablename not in ('flyway_schema_history', 'voucher_keys', 'preferences'))
     or (schemaname = 'dealer' and tablename in ('delivered_bookings', 'reviews', 'reliability_events'));
  execute 'truncate ' || t;
end $$;
SQL

echo "purging queues"
for q in $(docker compose exec -T localstack awslocal sqs list-queues --queue-name-prefix convoy- --query 'QueueUrls[]' --output text | tr -d '\r'); do
  [ "$q" = "None" ] || docker compose exec -T localstack awslocal sqs purge-queue --queue-url "$q" >/dev/null
done

echo "starting services (re-seed)"
docker compose up -d $apps >/dev/null
for s in identity pool payment order notification; do
  for i in $(seq 1 60); do docker compose exec -T "$s" wget -qO- localhost:8080/actuator/health 2>/dev/null | grep -q UP && break; sleep 3; done
done
echo "demo data reset: $(docker compose exec -T postgres psql -U convoy_admin -d convoy -tAc 'select count(*) from pool.pools') pools, $(docker compose exec -T postgres psql -U convoy_admin -d convoy -tAc 'select count(*) from payment.payments') payments"
