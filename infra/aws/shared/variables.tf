variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "profile" {
  description = "Tag value. lite: these resources serve the always-on demo (the full profile reuses them)."
  type        = string
  default     = "lite"
}

variable "site_origin" {
  description = "Public origin of the demo, for browser uploads of dispute evidence (CORS), e.g. https://convoy-demo.duckdns.org"
  type        = string
}

variable "alert_email" {
  description = "Budget and queue alarm emails go here (confirm the SNS subscription email once)."
  type        = string
}

variable "mail_from" {
  description = "Sender address. In the SES sandbox, verify it once (AWS emails a link). The SMTP user may send only from it."
  type        = string
}

variable "ses_domain" {
  description = "A domain you control, for SES. Empty: no domain identity; email sign-in stays off."
  type        = string
  default     = ""
}

variable "queue_age_alarms" {
  description = "Also alarm on each queue's oldest-message age (a stuck consumer). ~$3.30/month more than DLQ-only alarms."
  type        = bool
  default     = true
}
