#!/usr/bin/env bash
# P5.A isolation test (PLAN §3.3 "notification down -> join, quote, confirm and pay still work"), against the local stack:
#   1. stop notification; 2. a buyer joins, commits, pays and confirms (tools/journey/api-journey.mjs, through to the voucher);
#   3. start notification: the backlog must arrive exactly once - one in-app message and one SENT email per event, the same
#      number of mails in Mailpit; 4. restart it again and check nothing is sent twice.
# Usage: bash tools/notification/isolation.sh   (stack up, lean profile)
set -euo pipefail
cd "$(dirname "$0")/../.."
MAILPIT=${MAILPIT_URL:-http://localhost:8025}
BUYER=${BUYER_EMAIL:-buyer1@convoy.test}
sql() { docker compose exec -T postgres psql -U convoy_admin -d convoy -tAc "$1"; }
mails() { curl -s "$MAILPIT/api/v1/search?query=$(node -e "process.stdout.write(encodeURIComponent('to:$BUYER subject:\"$1\"'))")&limit=1" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).messages_count))"; }

docker compose stop notification >/dev/null
echo "notification stopped"
# JOURNEY: any buyer journey that joins, commits, pays and confirms. The API journey needs the Razorpay fake; with real
# test mode use the browser one: JOURNEY="cd web && pnpm exec playwright test e2e/journey.spec.ts --project=mobile-360"
last=$(sql "select coalesce(max(id::text), '') from payment.payments")
bash -c "${JOURNEY:-node tools/journey/api-journey.mjs}"
pool=$(sql "select pool_id from payment.payments where id::text > '$last' order by id desc limit 1")
[ -n "$pool" ] || { echo "FAIL: the journey created no deposit"; exit 1; }
echo "journey passed with notification down: pool $pool"
before=$(mails "Confirm your offer")

docker compose start notification >/dev/null
echo "notification started; waiting for the backlog"
# The buyer's events for this pool (pool:, user: and order: keys all carry pool_id in the payload).
q_events="select count(*) from notification.events where payload->>'pool_id' = '$pool'"
q_notes="select count(*) from notification.notifications n join notification.events e using (event_id) where e.payload->>'pool_id' = '$pool'"
q_sent="select count(*) from notification.deliveries d join notification.notifications n on n.id = d.notification_id join notification.events e using (event_id) where e.payload->>'pool_id' = '$pool' and d.status = 'SENT'"
for i in $(seq 1 60); do
  e=$(sql "$q_events"); n=$(sql "$q_notes"); s=$(sql "$q_sent")
  [ "$e" -ge 8 ] && [ "$n" = "$e" ] && [ "$s" = "$n" ] && break
  sleep 3
done
echo "events $e, notifications $n, emails sent $s"
kinds=$(sql "select string_agg(kind, ',' order by kind) from notification.events where payload->>'pool_id' = '$pool'")
echo "kinds: $kinds"
for k in DEPOSIT_RECEIVED POOL_READY ROUND_OPENED FINAL_ROUND_OPENED ROUND_CLOSED COMPARISON_READY CONFIRMATION_NEEDED TIER_RESOLVED; do
  [[ ",$kinds," == *",$k,"* ]] || { echo "FAIL: no $k"; exit 1; }
done
[ "$n" = "$e" ] && [ "$s" = "$n" ] || { echo "FAIL: expected one message and one email per event"; exit 1; }
dups=$(sql "select count(*) from (select 1 from notification.notifications group by user_id, event_id having count(*) > 1) x")
[ "$dups" = 0 ] || { echo "FAIL: $dups duplicate notifications"; exit 1; }
sleep 3
after=$(mails "Confirm your offer")
[ $((after - before)) = 1 ] || { echo "FAIL: Mailpit got $((after - before)) 'Confirm your offer' mails for this run, expected 1"; exit 1; }

docker compose restart notification >/dev/null
sleep 20
n2=$(sql "$q_notes"); s2=$(sql "$q_sent"); again=$(mails "Confirm your offer")
[ "$n2" = "$n" ] && [ "$s2" = "$s" ] && [ "$again" = "$after" ] || { echo "FAIL: restart changed counts ($n2/$s2/$again)"; exit 1; }
echo "PASS: notification down did not block the buyer; backlog delivered exactly once ($n messages, $s emails, 1 confirm mail in Mailpit)"
