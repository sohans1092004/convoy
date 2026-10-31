# For the full profile (P6.E), applied and destroyed from GitHub Actions without stored keys:
# - a private, versioned bucket for its Terraform state (the scheduled destroy needs the state where GitHub can reach it)
# - GitHub's OIDC provider
# - a role that only this repo's `full` environment can assume
resource "aws_s3_bucket" "tfstate" {
  bucket = "convoy-tfstate-${local.account}"
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

variable "github_repo" {
  type    = string
  default = "sohans1092004/convoy"
}

resource "aws_iam_role" "github_full" {
  name                 = "convoy-github-full"
  max_session_duration = 14400 # covers a run plus its scheduled destroy (the guard job re-assumes anyway)
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:environment:full"
        }
      }
    }]
  })
}

# Building a whole VPC/ECS/RDS/edge stack needs broad rights: PowerUser (no IAM) plus IAM limited to convoy-full-* roles.
# ponytail: PowerUser is wide; narrow to the services the full stack uses if this account ever holds anything else.
resource "aws_iam_role_policy_attachment" "github_full_power" {
  role       = aws_iam_role.github_full.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

resource "aws_iam_role_policy" "github_full_iam" {
  name = "convoy-full-roles-only"
  role = aws_iam_role.github_full.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["iam:CreateRole", "iam:DeleteRole", "iam:GetRole", "iam:PassRole", "iam:TagRole", "iam:UntagRole", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole", "iam:PutRolePolicy", "iam:GetRolePolicy", "iam:DeleteRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy"]
        Resource = "arn:aws:iam::${local.account}:role/convoy-full-*"
      },
      { Effect = "Allow", Action = ["iam:CreateServiceLinkedRole"], Resource = "*" },
    ]
  })
}

output "tfstate_bucket" { value = aws_s3_bucket.tfstate.bucket }
output "github_full_role_arn" { value = aws_iam_role.github_full.arn }
