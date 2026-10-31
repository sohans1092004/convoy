# RDS PostgreSQL (single-AZ by default; var.multi_az only for the failover drill), ElastiCache Redis, one secret.
resource "aws_db_subnet_group" "db" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "pg" {
  identifier                   = local.name
  engine                       = "postgres"
  engine_version               = "16"
  instance_class               = var.db_instance_class
  allocated_storage            = 50
  storage_type                 = "gp3"
  storage_encrypted            = true
  db_name                      = "convoy"
  username                     = "convoy_admin"
  password                     = var.secrets["POSTGRES_PASSWORD"]
  multi_az                     = var.multi_az
  db_subnet_group_name         = aws_db_subnet_group.db.name
  vpc_security_group_ids       = [aws_security_group.data.id]
  backup_retention_period      = 0 # a run's data is disposable; no snapshot on destroy either
  skip_final_snapshot          = true
  deletion_protection          = false
  performance_insights_enabled = false
  apply_immediately            = true
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id         = local.name
  engine             = "redis"
  node_type          = "cache.t4g.small"
  num_cache_nodes    = 1
  port               = 6379
  subnet_group_name  = aws_elasticache_subnet_group.redis.name
  security_group_ids = [aws_security_group.data.id]
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.name}-${var.run_id}"
  recovery_window_in_days = 0 # gone at destroy, so the teardown inventory is empty
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id     = aws_secretsmanager_secret.app.id
  secret_string = jsonencode(var.secrets)
}
