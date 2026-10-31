variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "run_id" {
  description = "Tag on every resource of one run (scripts/full.sh sets it): the teardown inventory and the cost report filter on it."
  type        = string
}

variable "images" {
  description = "Service name -> image by digest (from the images workflow's images.env). Never a tag."
  type        = map(string)
  validation {
    condition     = alltrue([for i in values(var.images) : can(regex("@sha256:[0-9a-f]{64}$", i))])
    error_message = "Every image must be pinned by digest."
  }
}

variable "services_running" {
  description = "false on the first apply: the database is initialised (db-init task) before any service starts."
  type        = bool
  default     = true
}

variable "tasks_per_service" {
  type    = number
  default = 2
}

variable "multi_az" {
  description = "RDS Multi-AZ: only for the failover drill (P7). Single-AZ otherwise (half the RDS cost)."
  type        = bool
  default     = false
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "nat_per_az" {
  description = "One NAT gateway per AZ (AZ-failure drills). Default: one NAT for both AZs."
  type        = bool
  default     = false
}

variable "trace_sample_ratio" {
  type    = number
  default = 0.05
}

variable "shared" {
  description = "Outputs of infra/aws/shared: account_id, export_bucket, evidence_bucket, smtp_host, smtp_username, mail_from"
  type        = map(string)
}

variable "secrets" {
  description = "Service secrets (DB passwords, client secrets, data key, SMTP password, Razorpay fake keys). Stored in one Secrets Manager secret."
  type        = map(string)
  sensitive   = true
}
