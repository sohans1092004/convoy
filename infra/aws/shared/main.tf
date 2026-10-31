# Convoy shared AWS resources (P6.C). What the lite host uses, and the full profile reuses:
# - SNS/SQS exactly as the services create them locally (topology.json, written by scripts/dump-topology.py)
# - the export and evidence buckets
# - SES only with a domain
# - the lite host's least-privilege IAM user (+ an SES SMTP user)
# - budget alerts at $8 and $20 (on tag project=convoy)
# Applied by a person after reviewing `terraform plan` (README.md here).
terraform {
  required_version = ">= 1.10" # S3 native locking (use_lockfile)
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  # State lives in the encrypted, versioned bucket this config creates (migrated after the first apply, which had to run
  # with local state). Holds no secrets: the access keys are made outside Terraform (scripts/create-host-keys.sh).
  #   terraform init -backend-config=bucket=convoy-tfstate-<account>
  backend "s3" {
    key          = "shared/terraform.tfstate"
    region       = "ap-southeast-2"
    use_lockfile = true
    encrypt      = true
  }
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
