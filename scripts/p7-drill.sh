#!/usr/bin/env bash
# P7.C failure drills on a full run (each gets a postmortem in reports/p7/drills/). Needs a run applied with DRILLS=true.
#   scripts/p7-drill.sh <run> sql "<query>"          run psql in the VPC (toolbox task), print the result
#   scripts/p7-drill.sh <run> kill <service>         stop one running task of a service (drill 1: 2 tasks per service)
#   scripts/p7-drill.sh <run> failover               force an RDS Multi-AZ failover (drill 2: MULTI_AZ=true run)
#   scripts/p7-drill.sh <run> redis-flush            FLUSHALL on the run's Redis (drill 3)
#   scripts/p7-drill.sh <run> notify-pause|notify-resume   notification consumers to 0 / back (drill 4: SQS backlog)
#   scripts/p7-drill.sh <run> queue-age              oldest-message age and depth of the notification queue + its DLQ
set -euo pipefail
run=$1 cmd=$2; shift 2
root=$(cd "$(dirname "$0")/.." && pwd); dir="$root/infra/aws/full"
out() { (cd "$dir" && terraform output -raw "$1"); }
cluster=$(out cluster)
toolbox() { # command -> its log output
  local arn task
  arn=$(aws ecs run-task --cluster "$cluster" --launch-type FARGATE --task-definition "$(out toolbox_task)" \
    --network-configuration "awsvpcConfiguration={subnets=[$(out private_subnets)],securityGroups=[$(out tasks_security_group)]}" \
    --overrides "$(python3 -c 'import json,sys; print(json.dumps({"containerOverrides":[{"name":"toolbox","command":["sh","-c",sys.argv[1]]}]}))' "$1")" \
    --query 'tasks[0].taskArn' --output text)
  aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$arn"
  MSYS_NO_PATHCONV=1 aws logs get-log-events --log-group-name "/convoy-full/$run" --log-stream-name "toolbox/toolbox/${arn##*/}" --query 'events[].message' --output text | tr '\t' '\n'
}
case $cmd in
  sql) toolbox "psql -At -v ON_ERROR_STOP=1 -c \"$1\"" ;;
  redis-flush) toolbox "apk add -q redis >/dev/null && redis-cli -h \$REDIS_HOST FLUSHALL && redis-cli -h \$REDIS_HOST DBSIZE" ;;
  kill) t=$(aws ecs list-tasks --cluster "$cluster" --service-name "$1" --desired-status RUNNING --query 'taskArns[0]' --output text)
        aws ecs stop-task --cluster "$cluster" --task "$t" --reason "P7.C drill: kill one $1 task" --query 'task.lastStatus' --output text; echo "stopped ${t##*/} at $(date -u +%FT%TZ)" ;;
  failover) id=$(aws rds describe-db-instances --query "DBInstances[?TagList[?Key=='run' && Value=='$run']].DBInstanceIdentifier | [0]" --output text)
            [ "$(aws rds describe-db-instances --db-instance-identifier "$id" --query 'DBInstances[0].MultiAZ' --output text)" = True ] || { echo "refused: $id is not Multi-AZ (MULTI_AZ=true run)"; exit 2; }
            aws rds reboot-db-instance --db-instance-identifier "$id" --force-failover --query 'DBInstance.DBInstanceStatus' --output text; echo "failover started at $(date -u +%FT%TZ)" ;;
  notify-pause) aws ecs update-service --cluster "$cluster" --service notification --desired-count 0 --query 'service.desiredCount' --output text ;;
  notify-resume) aws ecs update-service --cluster "$cluster" --service notification --desired-count "${1:-1}" --query 'service.desiredCount' --output text ;;
  queue-age) for q in $(aws sqs list-queues --queue-name-prefix convoy-notification --query 'QueueUrls[]' --output text); do
               echo "$q $(aws sqs get-queue-attributes --queue-url "$q" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --query 'Attributes' --output json | tr -d '\n ')"
             done ;;
  *) echo "unknown drill command $cmd"; exit 2 ;;
esac
