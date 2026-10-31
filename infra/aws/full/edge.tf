# Browser -> CloudFront (HTTPS on *.cloudfront.net: no domain needed, so no ACM certificate) -> ALB (HTTP, CloudFront's
# origin-facing prefix list only, and a secret header) with a regional WAF -> web. Nothing is cached (SSE included).
# CloudFront-scoped WAF is not available in this account (it lives in us-east-1, which an SCP denies), so the WAF is
# REGIONAL on the ALB: every request still passes it, because the ALB accepts only CloudFront.
resource "random_password" "origin" {
  length  = 32
  special = false
}

resource "aws_lb" "alb" {
  name               = "${local.name}-${var.run_id}"
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
  idle_timeout       = 120 # SSE: heartbeats keep streams under this
}

resource "aws_lb_target_group" "web" {
  name        = "${local.name}-web-${var.run_id}"
  port        = 3000
  protocol    = "HTTP"
  vpc_id      = aws_vpc.v.id
  target_type = "ip"
  health_check {
    path    = "/"
    matcher = "200"
  }
  deregistration_delay = 30
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.alb.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 1
  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin.result]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

resource "aws_wafv2_web_acl" "edge" {
  name  = "${local.name}-${var.run_id}"
  scope = "REGIONAL"
  default_action {
    allow {}
  }
  rule {
    name     = "aws-common"
    priority = 1
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-common"
      sampled_requests_enabled   = false
    }
  }
  rule {
    name     = "per-ip-rate"
    priority = 2
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = 2000 # per 5 min per viewer IP; P7 load generators hit the ALB origin directly (cost estimate §2)
        aggregate_key_type = "FORWARDED_IP"
        # Behind CloudFront the ALB sees edge addresses; CloudFront adds the viewer's address to X-Forwarded-For.
        # ponytail: WAF reads the first address, which a client can pre-set; key on CloudFront-Viewer-Address if abused.
        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "per-ip-rate"
      sampled_requests_enabled   = false
    }
  }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "convoy-full"
    sampled_requests_enabled   = false
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.alb.arn
  web_acl_arn  = aws_wafv2_web_acl.edge.arn
}

resource "aws_cloudfront_distribution" "cdn" {
  enabled     = true
  price_class = "PriceClass_200" # includes India
  comment     = "convoy full ${var.run_id}"

  origin {
    domain_name = aws_lb.alb.dns_name
    origin_id   = "alb"
    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin.result
    }
    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 60
      origin_keepalive_timeout = 60
    }
  }

  default_cache_behavior {
    target_origin_id         = "alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = false                                  # SSE must not be buffered for compression
    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # Managed-CachingDisabled
    origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac" # Managed-AllViewerExceptHostHeader
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}

# ADOT collector: OTLP from the services -> X-Ray traces (sampled at the SDK, var.trace_sample_ratio) and only the
# metrics autoscaling needs -> CloudWatch EMF (custom metrics cost per metric; everything else is dropped).
locals {
  adot_config = yamlencode({
    receivers  = { otlp = { protocols = { http = { endpoint = "0.0.0.0:4318" } } } }
    processors = { batch = {} }
    exporters = {
      awsxray = { region = var.region }
      awsemf = {
        region                           = var.region
        namespace                        = "Convoy"
        log_group_name                   = "/convoy-full/${var.run_id}/emf"
        dimension_rollup_option          = "NoDimensionRollup"
        metric_declarations              = [{ dimensions = [["service.name"]], metric_name_selectors = ["^realtime\\.connections$"] }]
        resource_to_telemetry_conversion = { enabled = true }
      }
    }
    service = {
      pipelines = {
        traces  = { receivers = ["otlp"], processors = ["batch"], exporters = ["awsxray"] }
        metrics = { receivers = ["otlp"], processors = ["batch"], exporters = ["awsemf"] }
      }
    }
  })
}
