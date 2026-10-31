# Convoy full-profile reaper (P6.E): destroys a full run at its deadline without this laptop or GitHub.
# EventBridge Scheduler (one-time schedule per run, scripts/schedule-reap.sh) -> ECS RunTask: one Fargate task
# (0.25 vCPU, the official Terraform image pinned by digest) -> clones the repo at the run's exact commit ->
# scripts/reap.sh: terraform destroy, teardown verification by tag, report to convoy-alerts.
# Fargate, not CodeBuild: this account's CodeBuild concurrency quota is 0. `scripts/full.sh up` keeps 0.5 vCPU of the
# Fargate quota free so this task can always launch.
# The task role may change or delete only resources tagged project=convoy AND profile=full, plus the full-profile state
# objects; reads (Describe/List/Get, which AWS cannot scope by tag) are account-wide. Everything here is tagged
# profile=lite, so the reaper can never delete itself. Cost: docs/cost/aws-cost-estimate.md §6.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  # State in the shared state bucket (encrypted, versioned); the reaper's own role cannot read or write this key.
  #   terraform init -backend-config=bucket=convoy-tfstate-<account>
  backend "s3" {
    key          = "reaper/terraform.tfstate"
    region       = "ap-southeast-2"
    use_lockfile = true
    encrypt      = true
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
data "aws_availability_zones" "az" { state = "available" }

locals {
  account   = data.aws_caller_identity.me.account_id
  state     = "arn:aws:s3:::convoy-tfstate-${local.account}"
  tagged    = { StringEquals = { "aws:ResourceTag/project" = "convoy", "aws:ResourceTag/profile" = "full" } }
  log_group = "/convoy/reaper"
  # hashicorp/terraform:1.10.5, linux/amd64, pinned by digest
  image = "hashicorp/terraform@sha256:915e32d1042db927620d385142d29bd3956644a811f6c4201b3293f8ae65a4cc"
  # Only the exact commit the run was applied from: a branch or a short sha is refused, and HEAD must equal it.
  start = <<-SH
    set -eu
    apk add --no-cache bash aws-cli coreutils jq >/dev/null
    echo "$SOURCE_SHA" | grep -Eq '^[0-9a-f]{40}$' || { echo "refused: SOURCE_SHA must be a full commit sha"; exit 1; }
    git clone -q --no-checkout ${var.repo} /src && git -C /src checkout -q --detach "$SOURCE_SHA"
    [ "$(git -C /src rev-parse HEAD)" = "$SOURCE_SHA" ] || { echo "refused: checked-out commit differs"; exit 1; }
    echo "reaper code pinned at $SOURCE_SHA"
    cd /src && exec bash scripts/reap.sh "$TARGET_DIR" "$RUN_ID"
  SH
}

# ---------------------------------------------------------------- a tiny network: one public subnet, egress only
resource "aws_vpc" "reaper" {
  cidr_block = "10.70.0.0/24"
  tags       = { Name = "convoy-reaper" }
}

resource "aws_internet_gateway" "reaper" {
  vpc_id = aws_vpc.reaper.id
  tags   = { Name = "convoy-reaper" }
}

resource "aws_subnet" "reaper" {
  vpc_id            = aws_vpc.reaper.id
  cidr_block        = "10.70.0.0/26"
  availability_zone = data.aws_availability_zones.az.names[0]
  tags              = { Name = "convoy-reaper" }
}

resource "aws_route_table" "reaper" {
  vpc_id = aws_vpc.reaper.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.reaper.id
  }
  tags = { Name = "convoy-reaper" }
}

resource "aws_route_table_association" "reaper" {
  subnet_id      = aws_subnet.reaper.id
  route_table_id = aws_route_table.reaper.id
}

resource "aws_security_group" "reaper" {
  name        = "convoy-reaper"
  description = "Reaper task: no inbound, HTTPS and DNS out"
  vpc_id      = aws_vpc.reaper.id
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------- the task and its roles
resource "aws_cloudwatch_log_group" "reaper" {
  name              = local.log_group
  retention_in_days = 30
}

resource "aws_ecs_cluster" "reaper" {
  name = "convoy-reaper"
}

resource "aws_iam_role" "reaper" {
  name = "convoy-reaper"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole"
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
    ]
  })
}

# Execution role: write the task's own logs (the image is public, no registry credentials)
resource "aws_iam_role" "exec" {
  name               = "convoy-reaper-exec"
  assume_role_policy = aws_iam_role.reaper.assume_role_policy
}

resource "aws_iam_role_policy" "exec" {
  name   = "own-logs"
  role   = aws_iam_role.exec.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.reaper.arn}:*" }] })
}

resource "aws_ecs_task_definition" "reaper" {
  family                   = "convoy-reaper"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  task_role_arn            = aws_iam_role.reaper.arn
  execution_role_arn       = aws_iam_role.exec.arn
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name        = "reaper"
    image       = local.image
    essential   = true
    entryPoint  = ["sh", "-c"]
    command     = [local.start]
    environment = [{ name = "AWS_REGION", value = var.region }] # TARGET_DIR, RUN_ID, SOURCE_SHA come per run (schedule-reap.sh)
    logConfiguration = {
      logDriver = "awslogs"
      options   = { awslogs-group = local.log_group, awslogs-region = var.region, awslogs-stream-prefix = "reaper" }
    }
  }])
}

# EventBridge Scheduler may run this one task definition, in this cluster, with these two roles, nothing else.
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
  name = "run-reaper"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow", Action = "ecs:RunTask"
        Resource  = "arn:aws:ecs:${var.region}:${local.account}:task-definition/convoy-reaper:*"
        Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.reaper.arn } }
      },
      { Effect = "Allow", Action = "iam:PassRole", Resource = [aws_iam_role.reaper.arn, aws_iam_role.exec.arn] },
    ]
  })
}

output "cluster" { value = aws_ecs_cluster.reaper.name }
output "subnet" { value = aws_subnet.reaper.id }
output "security_group" { value = aws_security_group.reaper.id }
output "reaper_role" { value = aws_iam_role.reaper.arn }
