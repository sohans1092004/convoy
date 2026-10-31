# Browser -> ALB (HTTPS, 2 AZs) with a regional WAF -> web. No CloudFront: this account must be verified by AWS
# Support before it may create CloudFront resources. Without a domain there is no publicly trusted certificate, so the
# ALB serves a SELF-SIGNED certificate (made here, imported into ACM). Consequences, by design for this profile only:
# browsers warn, passkeys do not work, and the journey and k6 skip TLS verification (FULL_SELF_SIGNED=1). The public
# demo is the OCI host, with a real Let's Encrypt certificate (docs/runbooks/full-profile.md).
resource "tls_private_key" "alb" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "alb" {
  private_key_pem       = tls_private_key.alb.private_key_pem
  validity_period_hours = 48
  allowed_uses          = ["key_encipherment", "digital_signature", "server_auth"]
  dns_names             = [aws_lb.alb.dns_name]
  subject {
    common_name  = aws_lb.alb.dns_name
    organization = "Convoy full profile (self-signed, test only)"
  }
}

resource "aws_acm_certificate" "alb" {
  private_key      = tls_private_key.alb.private_key_pem
  certificate_body = tls_self_signed_cert.alb.cert_pem
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

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.alb.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.alb.arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.alb.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
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
        aggregate_key_type = "IP" # the ALB is reached directly: the connecting address is the client
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
