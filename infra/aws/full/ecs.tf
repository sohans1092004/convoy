# ECS Fargate (ARM64), Service Connect (namespace "convoy": http://identity:8080 etc., as in compose), >= 2 tasks each.
# Same images and the same prod profile as lite: the topology is checked, never created, and config is validated at start.
locals {
  # A destroy (scripts/reap.sh) passes no images; the placeholder is never deployed (image values are validated on apply).
  log_group  = "/convoy-full/${var.run_id}"
  db_host    = var.canary ? "canary-no-database" : aws_db_instance.pg[0].address
  redis_host = var.canary ? "canary-no-redis" : aws_elasticache_cluster.redis[0].cache_nodes[0].address

  common_env = {
    SPRING_PROFILES_ACTIVE                  = "prod"
    AWS_REGION                              = var.region
    CONVOY_AWS_ACCOUNT_ID                   = local.account
    DB_URL                                  = "jdbc:postgresql://pgbouncer:5432/convoy?prepareThreshold=0"
    SPRING_FLYWAY_URL                       = "jdbc:postgresql://${local.db_host}:5432/convoy" # migrations bypass PgBouncer (session advisory lock)
    DB_POOL_SIZE                            = "8"
    GATEWAY_JWKS                            = "http://gateway:8080/.well-known/jwks.json"
    IDENTITY_JWKS                           = "http://identity:8080/.well-known/jwks.json"
    IDENTITY_URL                            = "http://identity:8080"
    DEMO                                    = "true"
    CONVOY_MESSAGING_POLLERS                = "2"
    CONVOY_MESSAGING_WAIT_SECONDS           = "20"
    CONVOY_MESSAGING_DLQ_CHECK_INTERVAL     = "5m"
    OTEL_EXPORTER_OTLP_ENDPOINT             = "http://adot:4318"
    OTEL_EXPORTER_OTLP_PROTOCOL             = "http/protobuf"
    OTEL_TRACES_SAMPLER                     = "parentbased_traceidratio"
    OTEL_TRACES_SAMPLER_ARG                 = tostring(var.trace_sample_ratio)
    OTEL_METRICS_EXPORTER                   = "otlp"
    OTEL_INSTRUMENTATION_MICROMETER_ENABLED = "true" # realtime.connections reaches CloudWatch (EMF) for autoscaling
    OTEL_LOGS_EXPORTER                      = "none"
    JAVA_TOOL_OPTIONS                       = "-javaagent:/opt/otel-agent.jar -XX:+UseSerialGC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
  }
  mail_env = {
    SMTP_HOST     = var.shared["smtp_host"], SMTP_PORT = "587", SMTP_USERNAME = var.shared["smtp_username"], SMTP_AUTH = "true"
    SMTP_STARTTLS = "true", MAIL_FROM = var.shared["mail_from"]
  }
  public_url = "https://${aws_lb.alb.dns_name}"

  # name => image key, port, cpu, memory, scaling (cpu | connections | queue), env, secret keys (from the run's secret)
  services = {
    identity = { port = 8080, cpu = 512, mem = 1024, scale = "cpu", env = merge(local.mail_env, {
      EXPORT_BUCKET = var.shared["export_bucket"], WEB_URL = local.public_url, PASSKEY_RP_ID = aws_lb.alb.dns_name,
      PASSKEY_ORIGINS = local.public_url, DEMO_SCENARIOS = "true", EMAIL_SIGN_IN = "false" }),
      secrets = ["DB_PASSWORD_IDENTITY:DB_PASSWORD", "IDENTITY_DATA_KEY", "SMTP_PASSWORD", "CLIENT_SECRET_GATEWAY", "CLIENT_SECRET_REALTIME", "CLIENT_SECRET_POOL",
    "CLIENT_SECRET_ORDER", "CLIENT_SECRET_NOTIFICATION", "CLIENT_SECRET_CATALOG", "CLIENT_SECRET_DEALER", "CLIENT_SECRET_QUOTE", "CLIENT_SECRET_PAYMENT", "CLIENT_SECRET_DEMO_BOTS"] }
    gateway = { port = 8080, cpu = 512, mem = 1024, scale = "cpu", env = { REDIS_HOST = local.redis_host,
    GATEWAY_KEY_DIR = "/tmp/keys", REALTIME_1_URL = "http://realtime:8080", REALTIME_2_URL = "http://realtime:8080" }, secrets = ["CLIENT_SECRET_GATEWAY"] }
    catalog       = { port = 8080, cpu = 256, mem = 1024, scale = "cpu", env = {}, secrets = ["DB_PASSWORD_CATALOG:DB_PASSWORD", "CLIENT_SECRET_CATALOG"] }
    dealer        = { port = 8080, cpu = 256, mem = 1024, scale = "cpu", env = { DEMO_SCENARIOS = "true" }, secrets = ["DB_PASSWORD_DEALER:DB_PASSWORD", "CLIENT_SECRET_DEALER"] }
    pool          = { port = 8080, cpu = 512, mem = 1024, scale = "cpu", env = { DEMO_SEALED_ROUND_SECONDS = "90", DEMO_FINAL_ROUND_SECONDS = "20", DEMO_DECISION_SECONDS = "60", DEMO_CONFIRMATION_SECONDS = "60", POOL_MIN_MEMBERS = "1" }, secrets = ["DB_PASSWORD_POOL:DB_PASSWORD", "CLIENT_SECRET_POOL"] }
    quote         = { port = 8080, cpu = 512, mem = 1024, scale = "cpu", env = {}, secrets = ["DB_PASSWORD_QUOTE:DB_PASSWORD", "CLIENT_SECRET_QUOTE"] }
    order         = { port = 8080, cpu = 256, mem = 1024, scale = "cpu", env = { EVIDENCE_BUCKET = var.shared["evidence_bucket"] }, secrets = ["DB_PASSWORD_ORDER:DB_PASSWORD", "CLIENT_SECRET_ORDER"] }
    payment       = { port = 8080, cpu = 256, mem = 1024, scale = "cpu", env = { RAZORPAY_BASE_URL = "http://razorpay-fake:9090" }, secrets = ["DB_PASSWORD_PAYMENT:DB_PASSWORD", "CLIENT_SECRET_PAYMENT", "RAZORPAY_KEY_ID", "RAZORPAY_KEY_SECRET", "RAZORPAY_WEBHOOK_SECRET"] }
    realtime      = { port = 8080, cpu = 512, mem = 1024, scale = "connections", env = { REDIS_HOST = local.redis_host, REALTIME_INSTANCE = "ecs" }, secrets = ["DB_PASSWORD_REALTIME:DB_PASSWORD", "CLIENT_SECRET_REALTIME"] }
    notification  = { port = 8080, cpu = 256, mem = 1024, scale = "queue", env = merge(local.mail_env, { WEB_URL = local.public_url }), secrets = ["DB_PASSWORD_NOTIFICATION:DB_PASSWORD", "CLIENT_SECRET_NOTIFICATION", "SMTP_PASSWORD"] }
    audit         = { port = 8080, cpu = 256, mem = 1024, scale = "cpu", env = {}, secrets = ["DB_PASSWORD_AUDIT:DB_PASSWORD", "DB_PASSWORD_AUDIT_OWNER:DB_OWNER_PASSWORD"] }
    web           = { port = 3000, cpu = 256, mem = 512, scale = "cpu", env = { GATEWAY_URL = "http://gateway:8080", RAZORPAY_CHECKOUT_URL = "${local.public_url}/pay/checkout.js", DEMO_PUBLIC = "true", EMAIL_SIGN_IN = "false" }, secrets = ["RAZORPAY_KEY_ID"] }
    razorpay-fake = { port = 9090, cpu = 256, mem = 512, scale = "cpu", env = { WEBHOOK_URL = "http://gateway:8080/webhooks/razorpay", PUBLIC_URL = "${local.public_url}/pay" }, secrets = ["RAZORPAY_KEY_ID", "RAZORPAY_KEY_SECRET", "RAZORPAY_WEBHOOK_SECRET"] }
    demo-bots     = { port = 0, cpu = 256, mem = 512, scale = "none", env = { GATEWAY_URL = "http://gateway:8080", IDENTITY_URL = "http://identity:8080" }, secrets = ["CLIENT_SECRET_DEMO_BOTS"] }
    # PgBouncer (transaction pooling) in front of RDS: per-service logins from the run's secret; TLS to RDS (rds.force_ssl).
    # RDS does not let auth_query read password hashes, so the userlist is written from the secrets at start.
    pgbouncer = { port = 5432, cpu = 256, mem = 512, scale = "cpu", env = { DB_HOST = local.db_host }, secrets = concat(["POSTGRES_PASSWORD"],
    [for s in ["IDENTITY", "CATALOG", "POOL", "DEALER", "QUOTE", "ORDER", "PAYMENT", "REALTIME", "NOTIFICATION", "AUDIT", "AUDIT_OWNER"] : "DB_PASSWORD_${s}"]) }
    adot = { port = 4318, cpu = 256, mem = 512, scale = "none", env = { AOT_CONFIG_CONTENT = local.adot_config }, secrets = [] }
  }
  pgbouncer_start = join(" && ", [
    "apk add --no-cache pgbouncer >/dev/null",
    "for u in identity catalog pool dealer quote order payment realtime notification audit audit_owner convoy_admin; do v=DB_PASSWORD_$(echo $u | tr a-z A-Z); [ $u = convoy_admin ] && v=POSTGRES_PASSWORD; printf '\"%s\" \"%s\"\n' $u \"$(printenv $v)\"; done > /tmp/userlist.txt",
    "printf '[databases]\nconvoy = host=%s port=5432 dbname=convoy\n[pgbouncer]\nlisten_addr = 0.0.0.0\nlisten_port = 5432\nauth_type = scram-sha-256\nauth_file = /tmp/userlist.txt\npool_mode = transaction\nmax_client_conn = 2000\ndefault_pool_size = 20\nserver_tls_sslmode = require\nignore_startup_parameters = extra_float_digits\n' \"$DB_HOST\" > /tmp/pgbouncer.ini",
    "exec pgbouncer -u nobody /tmp/pgbouncer.ini",
  ])
  # Spring services read their own secrets; the JVM env sets apply to the Spring services only
  jvm     = toset(["identity", "gateway", "catalog", "dealer", "pool", "quote", "order", "payment", "realtime", "notification", "audit"])
  desired = { for k, v in local.services : k => var.services_running ? (contains(["adot", "demo-bots"], k) ? 1 : var.tasks_per_service) : 0 }
  # Worst case on Fargate (every scalable service at its ceiling, + db-init/seed 0.25): scripts/full.sh refuses a run
  # that would leave less than 0.5 vCPU of the account's on-demand quota for the reaper task (0.25 vCPU).
  worst_case_vcpu = (sum([for k, v in local.services : v.cpu * (v.scale == "none" ? 1 : max(var.tasks_per_service, var.max_tasks_per_service))]) + 256) / 1024
}

resource "aws_cloudwatch_log_group" "l" {
  name              = local.log_group
  retention_in_days = 1
}

resource "aws_service_discovery_http_namespace" "ns" {
  name = "convoy"
}

resource "aws_ecs_cluster" "c" {
  name = "${local.name}-${var.run_id}"
  service_connect_defaults { namespace = aws_service_discovery_http_namespace.ns.arn }
  setting {
    name  = "containerInsights"
    value = "disabled" # $; the autoscaling signals come from SQS, EMF and the ECS service CPU metric
  }
}

resource "aws_iam_role" "exec" {
  name               = "convoy-full-exec"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}

resource "aws_iam_role_policy_attachment" "exec" {
  role       = aws_iam_role.exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "exec_secret" {
  name   = "read-run-secret"
  role   = aws_iam_role.exec.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = aws_secretsmanager_secret.app.arn }] })
}

# What the services call: the same rights as the lite host (shared), plus traces and EMF metrics for ADOT.
resource "aws_iam_role" "task" {
  name               = "convoy-full-task"
  assume_role_policy = aws_iam_role.exec.assume_role_policy
}

resource "aws_iam_role_policy" "task" {
  name = "convoy-services"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["sns:Publish", "sns:GetTopicAttributes", "sns:ListSubscriptionsByTopic", "sns:GetSubscriptionAttributes"], Resource = "arn:aws:sns:${var.region}:${local.account}:convoy-*" },
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage"], Resource = "arn:aws:sqs:${var.region}:${local.account}:convoy-*" },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = ["arn:aws:s3:::${var.shared["export_bucket"]}/*", "arn:aws:s3:::${var.shared["evidence_bucket"]}/*"] },
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = ["arn:aws:s3:::${var.shared["export_bucket"]}", "arn:aws:s3:::${var.shared["evidence_bucket"]}"] },
      { Effect = "Allow", Action = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets", "cloudwatch:PutMetricData", "logs:PutLogEvents", "logs:CreateLogStream", "logs:CreateLogGroup", "logs:DescribeLogStreams"], Resource = "*" },
    ]
  })
}

resource "aws_ecs_task_definition" "t" {
  for_each                 = local.services
  family                   = "convoy-full-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.mem
  execution_role_arn       = aws_iam_role.exec.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name         = each.key
    image        = lookup(var.images, each.key, "destroy-only@sha256:0000000000000000000000000000000000000000000000000000000000000000")
    essential    = true
    command      = each.key == "pgbouncer" ? ["sh", "-c", local.pgbouncer_start] : null
    portMappings = each.value.port == 0 ? [] : [{ name = each.key, containerPort = each.value.port, protocol = "tcp", appProtocol = contains(["pgbouncer"], each.key) ? null : "http" }]
    environment  = [for k, v in merge(contains(local.jvm, each.key) ? local.common_env : {}, each.value.env) : { name = k, value = v }]
    secrets = [for s in each.value.secrets : {
      name      = length(split(":", s)) > 1 ? split(":", s)[1] : s
      valueFrom = "${aws_secretsmanager_secret.app.arn}:${split(":", s)[0]}::"
    }]
    healthCheck = contains(local.jvm, each.key) ? {
      command = ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1"], interval = 10, timeout = 3, retries = 3, startPeriod = 120
    } : null
    logConfiguration = { logDriver = "awslogs", options = { awslogs-group = local.log_group, awslogs-region = var.region, awslogs-stream-prefix = each.key } }
  }])
}

resource "aws_ecs_service" "s" {
  for_each               = local.services
  name                   = each.key
  cluster                = aws_ecs_cluster.c.id
  task_definition        = aws_ecs_task_definition.t[each.key].arn
  desired_count          = local.desired[each.key]
  launch_type            = "FARGATE"
  enable_execute_command = false
  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.tasks.id]
  }
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.ns.arn
    dynamic "service" {
      for_each = each.value.port == 0 ? [] : [each.key]
      content {
        port_name = each.key
        client_alias {
          port     = each.value.port
          dns_name = each.key
        }
      }
    }
  }
  dynamic "load_balancer" {
    for_each = each.key == "web" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.web.arn
      container_name   = "web"
      container_port   = 3000
    }
  }
  lifecycle {
    ignore_changes = [desired_count] # autoscaling owns it after the first apply
  }
}

# ---------------------------------------------------------------- autoscaling, each service on its own signal
resource "aws_appautoscaling_target" "s" {
  for_each           = { for k, v in local.services : k => v if v.scale != "none" && var.services_running }
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.c.name}/${aws_ecs_service.s[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.tasks_per_service
  max_capacity       = max(var.tasks_per_service, var.max_tasks_per_service)
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = { for k, v in aws_appautoscaling_target.s : k => v if local.services[k].scale == "cpu" }
  name               = "${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension
  target_tracking_scaling_policy_configuration {
    target_value = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}

# realtime: open SSE connections per task (realtime.connections via ADOT EMF), target 2,000 per task
resource "aws_appautoscaling_policy" "connections" {
  for_each           = { for k, v in aws_appautoscaling_target.s : k => v if local.services[k].scale == "connections" }
  name               = "realtime-connections"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension
  target_tracking_scaling_policy_configuration {
    target_value = 2000
    customized_metric_specification {
      namespace   = "Convoy"
      metric_name = "realtime.connections"
      statistic   = "Average"
      dimensions {
        name  = "service.name"
        value = "realtime"
      }
    }
  }
}

# notification: messages waiting in its queues (SQS backlog). Step scaling: alarms cannot use SEARCH, so the queue names
# come from the shared topology; +1 task above 200 waiting, -1 below 10.
locals {
  notification_queues = [for q in jsondecode(file("${path.module}/../shared/topology.json")).queues : q.name if startswith(q.name, "convoy-notification--")]
}

resource "aws_appautoscaling_policy" "queue" {
  for_each           = { for d in ["out", "in"] : d => d if contains(keys(aws_appautoscaling_target.s), "notification") }
  name               = "notification-backlog-${each.key}"
  policy_type        = "StepScaling"
  service_namespace  = "ecs"
  resource_id        = aws_appautoscaling_target.s["notification"].resource_id
  scalable_dimension = aws_appautoscaling_target.s["notification"].scalable_dimension
  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 120
    metric_aggregation_type = "Maximum"
    step_adjustment {
      metric_interval_lower_bound = each.key == "out" ? 0 : null
      metric_interval_upper_bound = each.key == "in" ? 0 : null
      scaling_adjustment          = each.key == "out" ? 1 : -1
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "notification_backlog" {
  for_each            = aws_appautoscaling_policy.queue
  alarm_name          = "convoy-full-notification-backlog-${each.key}"
  comparison_operator = each.key == "out" ? "GreaterThanThreshold" : "LessThanThreshold"
  threshold           = each.key == "out" ? 200 : 10
  evaluation_periods  = 2
  alarm_actions       = [each.value.arn]
  metric_query {
    id          = "backlog"
    expression  = "SUM(METRICS())"
    label       = "notification backlog"
    return_data = true
  }
  dynamic "metric_query" {
    for_each = local.notification_queues
    content {
      id = "q${metric_query.key}"
      metric {
        namespace   = "AWS/SQS"
        metric_name = "ApproximateNumberOfMessagesVisible"
        dimensions  = { QueueName = metric_query.value }
        period      = 60
        stat        = "Maximum"
      }
    }
  }
}

# ---------------------------------------------------------------- one-off: create schemas and roles on RDS (db-init)
resource "aws_ecs_task_definition" "db_init" {
  family                   = "convoy-full-db-init"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.exec.arn
  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name      = "db-init"
    image     = lookup(var.images, "postgres", "destroy-only@sha256:0000000000000000000000000000000000000000000000000000000000000000")
    essential = true
    command   = ["sh", "-c", "echo \"$S1\" | base64 -d > /tmp/1.sh && echo \"$S2\" | base64 -d > /tmp/2.sh && sh /tmp/1.sh && sh /tmp/2.sh"]
    environment = [
      { name = "S1", value = base64encode(file("${path.module}/../../../deploy/local/postgres/01-schemas.sh")) },
      { name = "S2", value = base64encode(file("${path.module}/../../../deploy/local/postgres/02-audit.sh")) },
      { name = "PGHOST", value = local.db_host }, { name = "POSTGRES_USER", value = "convoy_admin" }, { name = "POSTGRES_DB", value = "convoy" },
    ]
    secrets = [for k in ["POSTGRES_PASSWORD:PGPASSWORD", "DB_PASSWORD_IDENTITY", "DB_PASSWORD_CATALOG", "DB_PASSWORD_POOL", "DB_PASSWORD_DEALER", "DB_PASSWORD_QUOTE", "DB_PASSWORD_ORDER",
      "DB_PASSWORD_PAYMENT", "DB_PASSWORD_REALTIME", "DB_PASSWORD_NOTIFICATION", "DB_PASSWORD_AUDIT", "DB_PASSWORD_AUDIT_OWNER"] : {
      name = length(split(":", k)) > 1 ? split(":", k)[1] : k, valueFrom = "${aws_secretsmanager_secret.app.arn}:${split(":", k)[0]}::"
    }]
    logConfiguration = { logDriver = "awslogs", options = { awslogs-group = local.log_group, awslogs-region = var.region, awslogs-stream-prefix = "db-init" } }
  }])
}

# The demo's synthetic dealerships (the same SQL the local stack and lite use), as the dealer role, once dealer has migrated.
resource "aws_ecs_task_definition" "seed" {
  family                   = "convoy-full-seed"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.exec.arn
  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name             = "seed"
    image            = lookup(var.images, "postgres", "destroy-only@sha256:0000000000000000000000000000000000000000000000000000000000000000")
    essential        = true
    command          = ["sh", "-c", "echo \"$SQL\" | base64 -d | psql -v ON_ERROR_STOP=1 -h \"$PGHOST\" -U dealer -d convoy"]
    environment      = [{ name = "SQL", value = base64encode(file("${path.module}/../../../deploy/local/seed/dealerships.sql")) }, { name = "PGHOST", value = local.db_host }]
    secrets          = [{ name = "PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:DB_PASSWORD_DEALER::" }]
    logConfiguration = { logDriver = "awslogs", options = { awslogs-group = local.log_group, awslogs-region = var.region, awslogs-stream-prefix = "seed" } }
  }])
}
