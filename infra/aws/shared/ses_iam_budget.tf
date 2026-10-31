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
# objects in the two buckets. Nothing can create, delete or reconfigure a resource. Its access key is not made by
# Terraform (it would sit in the state): scripts/create-host-keys.sh writes it straight into the git-ignored env file.
locals {
  # Name patterns, not ARN lists: IAM caps a user's inline policy at 2,048 bytes. They match exactly the event topics
  # (convoy-<producer>-events[.fifo], not convoy-alerts) and the consumer queues and DLQs (convoy-<consumer>--...).
  topic_arns = ["arn:aws:sns:${var.region}:${local.account}:convoy-*-events*"]
  queue_arns = ["arn:aws:sqs:${var.region}:${local.account}:convoy-*--*"]
}

resource "aws_iam_user" "host" {
  name          = "convoy-lite-host"
  force_destroy = true # its keys come from scripts/create-host-keys.sh, outside Terraform: destroy removes them too
}

resource "aws_iam_user_policy" "host" {
  name = "convoy-lite-host"
  user = aws_iam_user.host.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "Publish", Effect = "Allow", Action = ["sns:Publish", "sns:GetTopicAttributes", "sns:ListSubscriptionsByTopic"], Resource = local.topic_arns },
      # Subscription ARNs are <topic arn>:<uuid>: scoped to Convoy's topics
      { Sid = "CheckSubscriptions", Effect = "Allow", Action = ["sns:GetSubscriptionAttributes"], Resource = ["arn:aws:sns:${var.region}:${local.account}:convoy-*-events*:*"] },
      {
        Sid    = "Consume", Effect = "Allow", Resource = local.queue_arns
        Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage"]
      },
      { Sid = "Objects", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = [for b in aws_s3_bucket.b : "${b.arn}/*"] },
      { Sid = "Buckets", Effect = "Allow", Action = ["s3:ListBucket"], Resource = [for b in aws_s3_bucket.b : b.arn] },
    ]
  })
}


# SES SMTP: a separate user that can only send, only from var.mail_from. Its key and SMTP password: scripts/create-host-keys.sh.
resource "aws_iam_user" "smtp" {
  name          = "convoy-lite-smtp"
  force_destroy = true # its keys come from scripts/create-host-keys.sh, outside Terraform: destroy removes them too
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


# ---------------------------------------------------------------- budget alerts at $8 and $20 (actual), plus $20 forecast
# The expected bill is ~$4.66/month (docs/cost/aws-cost-estimate.md §6): $8 fires only on a real jump.
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
    for_each = [{ t = 8, kind = "ACTUAL" }, { t = 20, kind = "ACTUAL" }, { t = 20, kind = "FORECASTED" }]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.t
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = notification.value.kind
      subscriber_email_addresses = [var.alert_email]
    }
  }
}
