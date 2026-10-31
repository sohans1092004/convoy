# Convoy full profile (P6.E): brought up only for P7's load tests and failure drills, then destroyed.
# CloudFront (HTTPS only) -> ALB (2 AZs) with regional WAF -> web on ECS Fargate; every service >= 2 tasks with Service Connect; RDS PostgreSQL
# (single-AZ unless var.multi_az, for the failover drill) behind PgBouncer; ElastiCache Redis; ADOT -> X-Ray (sampled).
# SNS/SQS/S3 come from infra/aws/shared. Up and down only through scripts/full.sh (make full-up / full-down), which
# also schedules the destroy, verifies the teardown and reports the cost.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.80" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  backend "s3" {
    # bucket = convoy-tfstate-<account> (from shared), passed by scripts/full.sh with -backend-config
    key          = "full/terraform.tfstate"
    region       = "ap-southeast-2"
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "convoy", profile = "full", run = var.run_id }
  }
}

data "aws_caller_identity" "me" {}
data "aws_availability_zones" "az" { state = "available" }

locals {
  account = data.aws_caller_identity.me.account_id
  azs     = slice(data.aws_availability_zones.az.names, 0, 2)
  name    = "convoy-full"
}
