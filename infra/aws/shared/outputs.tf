# Values for the lite host's env file (/opt/convoy/convoy.env). Secrets are marked sensitive:
#   terraform output -raw host_secret_access_key   /   terraform output -raw smtp_password
output "account_id" { value = local.account }
output "export_bucket" { value = aws_s3_bucket.b["exports"].bucket }
output "evidence_bucket" { value = aws_s3_bucket.b["evidence"].bucket }
output "smtp_host" { value = "email-smtp.${var.region}.amazonaws.com" }
output "host_access_key_id" { value = aws_iam_access_key.host.id }
output "smtp_username" { value = aws_iam_access_key.smtp.id }

output "host_secret_access_key" {
  value     = aws_iam_access_key.host.secret
  sensitive = true
}

output "smtp_password" {
  value     = aws_iam_access_key.smtp.ses_smtp_password_v4
  sensitive = true
}

output "ses_dkim_cnames" {
  description = "Publish these at your DNS host (only with ses_domain)."
  value       = var.ses_domain == "" ? [] : [for t in aws_ses_domain_dkim.d[0].dkim_tokens : "${t}._domainkey.${var.ses_domain} CNAME ${t}.dkim.amazonses.com"]
}

output "counts" {
  value = { topics = length(aws_sns_topic.t), queues = length(aws_sqs_queue.q), dlqs = length(aws_sqs_queue.dlq), alarms = length(aws_cloudwatch_metric_alarm.queues) }
}
