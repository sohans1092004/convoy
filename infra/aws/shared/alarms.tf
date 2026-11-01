# Queue alarms, one metric-math alarm per 10 queues (the most metrics one alarm may read):
# - every DLQ not empty
# - the oldest message older than 15 minutes (its consumer is down or failing), only for var.queue_age_consumers
# Both email var.alert_email. CloudWatch bills every metric an alarm reads: 33 DLQs + 7 payment/order queues = 40 alarm
# metrics, 10 free, 30 x $0.10 = $3.00/month (docs/cost/aws-cost-estimate.md §6).
resource "aws_sns_topic" "alerts" {
  name = "convoy-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  aged = { dlq = sort(keys(local.queues)), stuck = sort([for n, q in local.queues : n if contains(var.queue_age_consumers, split("--", trimprefix(n, "convoy-"))[0])]) }
  alarms = {
    dlq   = { metric = "ApproximateNumberOfMessagesVisible", fn = "SUM", threshold = 0, dlq = true, what = "A DLQ has messages: fix the cause, then redrive (docs/runbooks/dlq-redrive.md)." }
    stuck = { metric = "ApproximateAgeOfOldestMessage", fn = "MAX", threshold = 900, dlq = false, what = "A queue's oldest message is over 15 min old: its consumer is down or failing." }
  }
  alarm_list = flatten([for k, a in local.alarms : [for i, c in chunklist(local.aged[k], 10) : { key = "${k}-${i + 1}", a = a, queues = c }]])
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
