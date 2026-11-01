variable "region" {
  description = "The only region this account may use (an organisation SCP denies the others)."
  type        = string
  default     = "ap-southeast-2"
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

variable "queue_age_consumers" {
  description = "Consumers whose queues also alarm on oldest-message age (a stuck consumer): the money path. Every DLQ alarms regardless."
  type        = list(string)
  default     = ["payment", "order"]
}
