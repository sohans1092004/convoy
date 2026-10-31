output "url" { value = "https://${aws_cloudfront_distribution.cdn.domain_name}" }
output "cluster" { value = aws_ecs_cluster.c.name }
output "db_init_task" { value = aws_ecs_task_definition.db_init.arn }
output "seed_task" { value = aws_ecs_task_definition.seed.arn }
output "private_subnets" { value = join(",", aws_subnet.private[*].id) }
output "tasks_security_group" { value = aws_security_group.tasks.id }
output "services" { value = keys(local.services) }
