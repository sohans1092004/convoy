# Convoy shared AWS resources (P6.C). What the lite host uses, and the full profile reuses:
# - SNS/SQS exactly as the services create them locally (topology.json, written by scripts/dump-topology.py)
# - the export and evidence buckets
# - SES only with a domain
# - the lite host's least-privilege IAM user (+ an SES SMTP user)
# - budget alerts at $5 and $20
# Applied by a person after reviewing `terraform plan` (README.md here).
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  # Local state (git-ignored): it holds the host's access key and SMTP password. Keep a private copy.
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "convoy", profile = var.profile }
  }
}

data "aws_caller_identity" "me" {}

locals {
  account  = data.aws_caller_identity.me.account_id
  topology = jsondecode(file("${path.module}/topology.json"))
  queues   = { for q in local.topology.queues : q.name => q }
}
