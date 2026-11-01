# A private, versioned bucket for the full profile's Terraform state (P6.E), so a scheduled destroy that does not run on
# this laptop can reach it. No GitHub OIDC provider: an organisation SCP denies iam:CreateOpenIDConnectProvider here.
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

output "tfstate_bucket" { value = aws_s3_bucket.tfstate.bucket }
