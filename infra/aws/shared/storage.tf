# Data exports (P5.D: one file per request, 15-min links, gone after 24 h) and dispute evidence (P4, retention.md).
# Private, encrypted (SSE-S3), public access blocked, ACLs off, TLS only. Names carry the account id (globally unique).
locals {
  buckets = {
    exports  = "convoy-data-exports-${local.account}"
    evidence = "convoy-dispute-evidence-${local.account}"
  }
}

resource "aws_s3_bucket" "b" {
  for_each = local.buckets
  bucket   = each.value
}

resource "aws_s3_bucket_public_access_block" "b" {
  for_each                = local.buckets
  bucket                  = aws_s3_bucket.b[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "b" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.b[each.key].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "b" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.b[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "tls_only" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.b[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "TlsOnly"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.b[each.key].arn, "${aws_s3_bucket.b[each.key].arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.b]
}

resource "aws_s3_bucket_lifecycle_configuration" "exports" {
  bucket = aws_s3_bucket.b["exports"].id
  rule {
    id     = "expire-after-24h"
    status = "Enabled"
    filter {}
    expiration {
      days = 1
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# Evidence is uploaded from the browser with a pre-signed PUT (E097), only from the demo's own origin.
resource "aws_s3_bucket_cors_configuration" "evidence" {
  bucket = aws_s3_bucket.b["evidence"].id
  cors_rule {
    allowed_methods = ["PUT", "GET"]
    allowed_origins = [var.site_origin]
    allowed_headers = ["*"]
    max_age_seconds = 600
  }
}
