# Queue alarms, one metric-math alarm per 10 queues (the most metrics one alarm may read). Two kinds: any DLQ not
# empty, and any queue whose oldest message is older than
# 15 minutes (its consumer is down or failing). Both email var.alert_email. Billing counts every metric an alarm reads,
# so grouping does not save money: 33 queues x 2 kinds = 66 alarm metrics (~$5.60/month after the free 10);
# queue_age_alarms = false keeps only the DLQ alarms (~$2.30). Cost: docs/cost/aws-cost-estimate.md §6.
resource "aws_sns_topic" "alerts" {
  name = "convoy-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  chunks = chunklist(sort(keys(local.queues)), 10)
  alarms = {
    dlq   = { metric = "ApproximateNumberOfMessagesVisible", fn = "SUM", threshold = 0, dlq = true, what = "A DLQ has messages: fix the cause, then redrive (docs/runbooks/dlq-redrive.md)." }
    stuck = { metric = "ApproximateAgeOfOldestMessage", fn = "MAX", threshold = 900, dlq = false, what = "A queue's oldest message is over 15 min old: its consumer is down or failing." }
  }
  alarm_list = flatten([for k, a in local.alarms : [for i, c in local.chunks : { key = "${k}-${i + 1}", a = a, queues = c }] if k == "dlq" || var.queue_age_alarms])
}

resource "aws_cloudwatch_metric_alarm" "queues" {
  for_each            = { for x in local.alarm_list : x.key => x }
  alarm_name          = "convoy-${each.key}"
  alarm_description   = "${each.value.a.what} Queues: ${join(", ", each.value.queues)}"
  comparison_operator = "GreaterThanThreshold"
  threshold           = each.value.a.threshold
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "agg"
    expression  = "${each.value.a.fn}(METRICS())"
    label       = each.key
    return_data = true
  }

  dynamic "metric_query" {
    for_each = each.value.queues
    content {
      id = "m${metric_query.key}"
      metric {
        namespace   = "AWS/SQS"
        metric_name = each.value.a.metric
        dimensions  = { QueueName = each.value.a.dlq ? local.queues[metric_query.value].dlq : metric_query.value }
        period      = 300
        stat        = "Maximum"
      }
    }
  }
}
