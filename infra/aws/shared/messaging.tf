# One topic per producer (+ .fifo where the catalogue says so). Per consumer queue: a DLQ (maxReceiveCount 5), raw
# delivery, a filter policy on event_type. Services check all of this at startup under prod (Topology.Missing).
resource "aws_sns_topic" "t" {
  for_each                    = toset(local.topology.topics)
  name                        = each.value
  fifo_topic                  = endswith(each.value, ".fifo")
  content_based_deduplication = false
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = local.queues
  name                      = each.value.dlq
  fifo_queue                = each.value.fifo
  message_retention_seconds = 1209600 # 14 days to fix the cause and redrive (docs/runbooks/dlq-redrive.md)
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "q" {
  for_each                   = local.queues
  name                       = each.key
  fifo_queue                 = each.value.fifo
  visibility_timeout_seconds = each.value.visibility_timeout
  receive_wait_time_seconds  = 20 # long polls: fewer billed empty receives
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn, maxReceiveCount = 5 })
}

resource "aws_sqs_queue_policy" "q" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.q[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.q[each.key].arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.t[each.value.topic].arn } }
    }]
  })
}

resource "aws_sns_topic_subscription" "s" {
  for_each             = local.queues
  topic_arn            = aws_sns_topic.t[each.value.topic].arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.q[each.key].arn
  raw_message_delivery = true
  filter_policy        = jsonencode({ event_type = each.value.event_types })
}
