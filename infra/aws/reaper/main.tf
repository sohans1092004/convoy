# Convoy full-profile reaper (P6.E): destroys a full run at its deadline without this laptop or GitHub.
# EventBridge Scheduler (one-time schedule per run, scripts/schedule-reap.sh) -> CodeBuild convoy-full-reaper -> clones the
# repo at the run's commit -> scripts/reap.sh: terraform destroy, teardown verification by tag, report to convoy-alerts.
# Its role may change or delete only resources tagged project=convoy AND profile=full, plus the full-profile state
# objects; reads (Describe/List/Get, which AWS cannot scope by tag) are account-wide. Tagged profile=lite, so the reaper
# can never delete itself. Local state (git-ignored), like infra/aws/shared. Cost: docs/cost/aws-cost-estimate.md §6.
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
}

variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "repo" {
  type    = string
  default = "https://github.com/sohans1092004/convoy.git"
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "convoy", profile = "lite" }
  }
}

data "aws_caller_identity" "me" {}

locals {
  account   = data.aws_caller_identity.me.account_id
  state     = "arn:aws:s3:::convoy-tfstate-${local.account}"
  tagged    = { StringEquals = { "aws:ResourceTag/project" = "convoy", "aws:ResourceTag/profile" = "full" } }
  tf        = "1.10.5"
  log_group = "/convoy/reaper"
}

resource "aws_cloudwatch_log_group" "reaper" {
  name              = local.log_group
  retention_in_days = 30
}

resource "aws_iam_role" "reaper" {
  name = "convoy-reaper"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Principal = { Service = "codebuild.amazonaws.com" }, Action = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account } }
    }]
  })
}

resource "aws_iam_role_policy" "reaper" {
  name = "destroy-full-only"
  role = aws_iam_role.reaper.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ChangeOnlyFullProfileResources"
        Effect = "Allow"
        Action = [
          "ec2:Delete*", "ec2:Disassociate*", "ec2:Detach*", "ec2:ReleaseAddress", "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress",
          "ecs:DeleteService", "ecs:UpdateService", "ecs:DeleteCluster", "ecs:DeregisterTaskDefinition", "ecs:DeleteTaskDefinitions", "ecs:StopTask",
          "elasticloadbalancing:Delete*", "elasticloadbalancing:DeregisterTargets",
          "rds:DeleteDBInstance", "rds:DeleteDBSubnetGroup", "elasticache:DeleteCacheCluster", "elasticache:DeleteCacheSubnetGroup",
          "secretsmanager:DeleteSecret", "cloudfront:UpdateDistribution", "cloudfront:DeleteDistribution",
          "wafv2:DeleteWebACL", "wafv2:DisassociateWebACL", "servicediscovery:DeleteNamespace",
          "application-autoscaling:DeregisterScalableTarget", "application-autoscaling:DeleteScalingPolicy",
          "cloudwatch:DeleteAlarms", "logs:DeleteLogGroup",
          "iam:DeleteRole", "iam:DeleteRolePolicy", "iam:DetachRolePolicy",
          "sqs:DeleteQueue",
        ]
        Resource  = "*"
        Condition = local.tagged
      },
      {
        Sid    = "ReadForRefreshAndVerification" # AWS does not scope these by tag
        Effect = "Allow"
        Action = [
          "sts:GetCallerIdentity", "tag:GetResources", "ce:GetCostAndUsage",
          "ec2:Describe*", "ecs:Describe*", "ecs:List*", "elasticloadbalancing:Describe*", "rds:Describe*", "rds:ListTagsForResource",
          "elasticache:Describe*", "elasticache:ListTagsForResource", "secretsmanager:DescribeSecret", "secretsmanager:GetResourcePolicy",
          "cloudfront:GetDistribution", "cloudfront:GetDistributionConfig", "cloudfront:ListTagsForResource",
          "wafv2:GetWebACL", "wafv2:GetWebACLForResource", "wafv2:ListTagsForResource", "servicediscovery:Get*", "servicediscovery:List*",
          "application-autoscaling:Describe*", "application-autoscaling:ListTagsForResource", "cloudwatch:DescribeAlarms", "cloudwatch:ListTagsForResource",
          "logs:DescribeLogGroups", "logs:ListTagsForResource", "logs:ListTagsLogGroup",
          "iam:GetRole", "iam:GetRolePolicy", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole",
          "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:ListQueueTags",
        ]
        Resource = "*"
      },
      { Sid = "StateBucket", Effect = "Allow", Action = ["s3:ListBucket"], Resource = local.state },
      { Sid = "StateObjects", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = ["${local.state}/full/*", "${local.state}/canary/*"] },
      { Sid = "Report", Effect = "Allow", Action = "sns:Publish", Resource = "arn:aws:sns:${var.region}:${local.account}:convoy-alerts" },
      { Sid = "OwnLogs", Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.reaper.arn}:*" },
    ]
  })
}

resource "aws_codebuild_project" "reaper" {
  name          = "convoy-full-reaper"
  description   = "Destroys a Convoy full-profile run at its deadline (scripts/reap.sh), verifies the teardown, reports"
  service_role  = aws_iam_role.reaper.arn
  build_timeout = 120 # CloudFront disable + delete alone can take ~15 min, RDS ~10
  artifacts {
    type = "NO_ARTIFACTS"
  }
  environment {
    compute_type = "BUILD_GENERAL1_SMALL"
    image        = "aws/codebuild/amazonlinux2-x86_64-standard:5.0"
    type         = "LINUX_CONTAINER"
    environment_variable {
      name  = "AWS_REGION"
      value = var.region
    }
    # Set per run by the schedule (schedule-reap.sh): TARGET_DIR, RUN_ID, SOURCE_SHA
  }
  logs_config {
    cloudwatch_logs {
      group_name = aws_cloudwatch_log_group.reaper.name
    }
  }
  source {
    type      = "NO_SOURCE"
    buildspec = <<-YAML
      version: 0.2
      env:
        shell: bash
      phases:
        install:
          commands:
            - curl -fsSLO https://releases.hashicorp.com/terraform/${local.tf}/terraform_${local.tf}_linux_amd64.zip
            - curl -fsSL https://releases.hashicorp.com/terraform/${local.tf}/terraform_${local.tf}_SHA256SUMS | grep linux_amd64.zip | sha256sum -c -
            - unzip -qo terraform_${local.tf}_linux_amd64.zip -d /usr/local/bin
            - git clone -q ${var.repo} src && git -C src checkout -q "$SOURCE_SHA"
        build:
          commands:
            - cd src && bash scripts/reap.sh "$TARGET_DIR" "$RUN_ID"
    YAML
  }
}

# EventBridge Scheduler may start this one project, nothing else.
resource "aws_iam_role" "scheduler" {
  name = "convoy-reaper-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Principal = { Service = "scheduler.amazonaws.com" }, Action = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account } }
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name   = "start-reaper"
  role   = aws_iam_role.scheduler.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "codebuild:StartBuild", Resource = aws_codebuild_project.reaper.arn }] })
}

output "project" { value = aws_codebuild_project.reaper.name }
output "reaper_role" { value = aws_iam_role.reaper.arn }
