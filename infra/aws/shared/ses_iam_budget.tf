# SES. Without a domain (var.ses_domain = ""), no identity is created: verify var.mail_from by hand (AWS emails a link),
# and email sign-in stays off (EMAIL_SIGN_IN=false). With a domain: its identity and DKIM tokens to publish as CNAMEs,
# then request SES production access before turning email sign-in on.
resource "aws_ses_domain_identity" "d" {
  count  = var.ses_domain == "" ? 0 : 1
  domain = var.ses_domain
}

resource "aws_ses_domain_dkim" "d" {
  count  = var.ses_domain == "" ? 0 : 1
  domain = aws_ses_domain_identity.d[0].domain
}

# ---------------------------------------------------------------- the lite host's IAM user (least privilege)
# Exactly the calls the services make: publish + check topics; receive/delete/extend/redrive and check queues;
# objects in the two buckets. Nothing can create, delete or reconfigure a resource. Rotation: README.md.
locals {
  topic_arns = [for t in aws_sns_topic.t : t.arn]
  queue_arns = concat([for q in aws_sqs_queue.q : q.arn], [for q in aws_sqs_queue.dlq : q.arn])
}

resource "aws_iam_user" "host" {
  name = "convoy-lite-host"
}

resource "aws_iam_user_policy" "host" {
  name = "convoy-lite-host"
  user = aws_iam_user.host.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "Publish", Effect = "Allow", Action = ["sns:Publish", "sns:GetTopicAttributes", "sns:ListSubscriptionsByTopic"], Resource = local.topic_arns },
      # Subscription ARNs are <topic arn>:<uuid>: scoped to Convoy's topics
      { Sid = "CheckSubscriptions", Effect = "Allow", Action = ["sns:GetSubscriptionAttributes"], Resource = [for a in local.topic_arns : "${a}:*"] },
      {
        Sid    = "Consume", Effect = "Allow", Resource = local.queue_arns
        Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage"]
      },
      { Sid = "Objects", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = [for b in aws_s3_bucket.b : "${b.arn}/*"] },
      { Sid = "Buckets", Effect = "Allow", Action = ["s3:ListBucket"], Resource = [for b in aws_s3_bucket.b : b.arn] },
    ]
  })
}

resource "aws_iam_access_key" "host" {
  user = aws_iam_user.host.name
}

# SES SMTP: a separate user that can only send, only from var.mail_from. Its SMTP password is derived by AWS from the key.
resource "aws_iam_user" "smtp" {
  name = "convoy-lite-smtp"
}

resource "aws_iam_user_policy" "smtp" {
  name = "convoy-lite-smtp"
  user = aws_iam_user.smtp.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Action = "ses:SendRawEmail", Resource = "*"
      Condition = { StringEquals = { "ses:FromAddress" = var.mail_from } }
    }]
  })
}

resource "aws_iam_access_key" "smtp" {
  user = aws_iam_user.smtp.name
}

# ---------------------------------------------------------------- budget alerts at $5 and $20 (actual), plus forecast
resource "aws_budgets_budget" "monthly" {
  name         = "convoy-monthly"
  budget_type  = "COST"
  limit_amount = "20"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Only Convoy's spend: the account also runs Concord (cost allocation tag `project` activated by you)
  cost_filter {
    name   = "TagKeyValue"
    values = ["user:project$convoy"]
  }

  dynamic "notification" {
    for_each = [{ t = 5, kind = "ACTUAL" }, { t = 20, kind = "ACTUAL" }, { t = 20, kind = "FORECASTED" }]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.t
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = notification.value.kind
      subscriber_email_addresses = [var.alert_email]
    }
  }
}
