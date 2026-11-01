# P7: the k6 load generator. One ARM EC2 instance in this run's VPC (public subnet, no inbound, driven over SSM),
# tagged with the run like everything else, so full-down and the reaper destroy it with the stack. Off by default.
# Load never comes from the laptop: the India-Sydney link would measure that network, not Convoy.
variable "loadgen_type" {
  description = "Instance type of the k6 generator; empty = none. P7 runs use c7g.xlarge or larger."
  type        = string
  default     = ""
}

data "aws_ssm_parameter" "al2023_arm" {
  count = var.loadgen_type == "" ? 0 : 1
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_iam_role" "loadgen" {
  count = var.loadgen_type == "" ? 0 : 1
  name  = "${local.name}-${var.run_id}-loadgen"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "loadgen_ssm" {
  count      = var.loadgen_type == "" ? 0 : 1
  role       = aws_iam_role.loadgen[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "loadgen_sessions" {
  count = var.loadgen_type == "" ? 0 : 1
  name  = "read-run-sessions"
  role  = aws_iam_role.loadgen[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = "s3:GetObject", Resource = "arn:aws:s3:::convoy-tfstate-${local.account}/full/${var.run_id}/*" },
    { Effect = "Allow", Action = "s3:PutObject", Resource = "arn:aws:s3:::convoy-tfstate-${local.account}/full/${var.run_id}/results/*" },
  ] })
}

resource "aws_iam_instance_profile" "loadgen" {
  count = var.loadgen_type == "" ? 0 : 1
  name  = "${local.name}-${var.run_id}-loadgen"
  role  = aws_iam_role.loadgen[0].name
}

resource "aws_security_group" "loadgen" {
  count  = var.loadgen_type == "" ? 0 : 1
  name   = "${local.name}-loadgen"
  vpc_id = aws_vpc.v.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "loadgen" {
  count                       = var.loadgen_type == "" ? 0 : 1
  ami                         = data.aws_ssm_parameter.al2023_arm[0].value
  instance_type               = var.loadgen_type
  subnet_id                   = aws_subnet.public[0].id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.loadgen[0].id]
  iam_instance_profile        = aws_iam_instance_profile.loadgen[0].name
  # k6 from the official release, pinned; raised file limits for 10,000+ connections
  user_data = <<-SH
    #!/bin/bash
    set -eu
    curl -fsSL https://github.com/grafana/k6/releases/download/v0.57.0/k6-v0.57.0-linux-arm64.tar.gz | tar xz -C /tmp
    install /tmp/k6-v0.57.0-linux-arm64/k6 /usr/local/bin/k6
    echo '* soft nofile 200000' >> /etc/security/limits.conf; echo '* hard nofile 200000' >> /etc/security/limits.conf
    sysctl -w net.ipv4.ip_local_port_range="1024 65000"
  SH
  root_block_device {
    volume_size = 20
    encrypted   = true
  }
  metadata_options { http_tokens = "required" }
  tags = { Name = "${local.name}-${var.run_id}-loadgen" }
}

output "loadgen_instance" { value = var.loadgen_type == "" ? "" : aws_instance.loadgen[0].id }
