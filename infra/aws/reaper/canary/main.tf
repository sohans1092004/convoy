# Reaper canary (P6.E): one empty SQS queue tagged like a full-profile run, to test the scheduled destroy end to end
# before a real run relies on it (scripts/reaper-canary.sh). Costs nothing (no requests).
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  backend "s3" {
    key          = "canary/terraform.tfstate"
    region       = "ap-southeast-2"
    use_lockfile = true
    encrypt      = true
  }
}

variable "run_id" {
  type = string
}

provider "aws" {
  region = "ap-southeast-2"
  default_tags {
    tags = { project = "convoy", profile = "full", run = var.run_id }
  }
}

resource "aws_sqs_queue" "canary" {
  name = "convoy-reaper-canary-${var.run_id}"
}
