# Values for the lite host's env file (/opt/convoy/convoy.env). No secrets: the access keys and the SMTP password are
# made by scripts/create-host-keys.sh, outside Terraform and its state.
output "account_id" { value = local.account }
output "host_user" { value = aws_iam_user.host.name }
output "smtp_user" { value = aws_iam_user.smtp.name }
output "export_bucket" { value = aws_s3_bucket.b["exports"].bucket }
output "evidence_bucket" { value = aws_s3_bucket.b["evidence"].bucket }
output "smtp_host" { value = "email-smtp.${var.region}.amazonaws.com" }



output "ses_dkim_cnames" {
  description = "Publish these at your DNS host (only with ses_domain)."
  value       = var.ses_domain == "" ? [] : [for t in aws_ses_domain_dkim.d[0].dkim_tokens : "${t}._domainkey.${var.ses_domain} CNAME ${t}.dkim.amazonses.com"]
}

output "counts" {
  value = { topics = length(aws_sns_topic.t), queues = length(aws_sqs_queue.q), dlqs = length(aws_sqs_queue.dlq), alarms = length(aws_cloudwatch_metric_alarm.queues) }
}
