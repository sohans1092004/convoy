# Interim lite host on EC2 (the Oracle A1 VM is out of capacity): one t4g.medium running deploy/lite/compose.yml exactly
# as on Oracle, Caddy + Let's Encrypt for convoy-main.duckdns.org. Own state, own small VPC; nothing shared with
# infra/aws/full or Concord. If an Oracle VM appears, the demo moves there and this stack is destroyed.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  backend "s3" {
    key          = "lite-ec2/terraform.tfstate"
    region       = "ap-southeast-2"
    use_lockfile = true
    encrypt      = true
  }
}

# Kept only because the instance's key_name cannot change without replacing the live host. No port accepts SSH.
variable "ssh_public_key" {
  description = "Public half of ~/.ssh/convoy-ec2 (the private key never leaves the laptop)"
  type        = string
}

provider "aws" {
  region = "ap-southeast-2"
  default_tags {
    tags = { project = "convoy", profile = "lite", host = "ec2" }
  }
}

data "aws_ssm_parameter" "ubuntu" { # Canonical Ubuntu 24.04 LTS, arm64 (bootstrap.sh is written for Ubuntu)
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}

data "aws_availability_zones" "az" { state = "available" }

resource "aws_vpc" "v" {
  cidr_block           = "10.80.0.0/24"
  enable_dns_hostnames = true
  tags                 = { Name = "convoy-lite" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.v.id
  tags   = { Name = "convoy-lite" }
}

resource "aws_subnet" "s" {
  vpc_id            = aws_vpc.v.id
  cidr_block        = "10.80.0.0/26"
  availability_zone = data.aws_availability_zones.az.names[0]
  tags              = { Name = "convoy-lite" }
}

resource "aws_route_table" "r" {
  vpc_id = aws_vpc.v.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
  tags = { Name = "convoy-lite" }
}

resource "aws_route_table_association" "r" {
  subnet_id      = aws_subnet.s.id
  route_table_id = aws_route_table.r.id
}

# The description below is out of date (port 22 is closed; access is Systems Manager only), but changing it replaces the group.
resource "aws_security_group" "host" {
  name        = "convoy-lite-host"
  description = "Convoy lite: web 80/443 public, SSH from the admin IP only"
  vpc_id      = aws_vpc.v.id
  dynamic "ingress" {
    for_each = [80, 443]
    content {
      description = "Caddy"
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "admin" {
  key_name   = "convoy-lite-admin"
  public_key = var.ssh_public_key
}

# Session Manager and Run Command reach the host with this role instead of SSH: nothing else is granted to the instance.
resource "aws_iam_role" "host" {
  name = "convoy-lite-host"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "host_ssm" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "host" {
  name = "convoy-lite-host"
  role = aws_iam_role.host.name
}

resource "aws_instance" "host" {
  ami                    = data.aws_ssm_parameter.ubuntu.value
  instance_type          = "t4g.medium" # 2 vCPU, 4 GiB: the lite stack idles at ~2.3 GiB (reports/p6/memory-laptop.md)
  subnet_id              = aws_subnet.s.id
  vpc_security_group_ids = [aws_security_group.host.id]
  key_name               = aws_key_pair.admin.key_name
  iam_instance_profile   = aws_iam_instance_profile.host.name
  # The live host was launched with a public IP (true); the Elastic IP below is the address DuckDNS points at. Changing
  # this forces replacement, so it matches what runs (P8: the plan wanted to replace the live host over it).
  associate_public_ip_address = true
  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }
  metadata_options { http_tokens = "required" }
  tags = { Name = "convoy-lite" }
  lifecycle {
    prevent_destroy = true             # the live demo and its database: never destroyed by a plan
    ignore_changes  = [ami, user_data] # a newer Ubuntu in the SSM parameter must not replace the running host
  }
}

# The only deploy path: "deploy this commit with these images", through the same checks SSH's forced command runs
# (deploy-ssh.sh validates the sha and that every image is a digest from this repo). Run from the laptop: scripts/deploy-lite.sh.
resource "aws_ssm_document" "deploy" {
  name            = "convoy-deploy"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Deploy one commit to the Convoy lite host (deploy-ssh.sh, then deploy.sh)."
    parameters = {
      sha    = { type = "String", description = "Full commit sha", allowedPattern = "^[0-9a-f]{40}$" }
      images = { type = "String", description = "images.env, base64", allowedPattern = "^[A-Za-z0-9+/=]+$", maxChars = 8192 }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "deploy"
      inputs = {
        timeoutSeconds = "1800" # a normal deploy takes about 5 minutes; a failed one also rolls back
        runCommand = [
          "set -eu", # SSM runs this with sh, which has no pipefail
          "tmp=$(mktemp)",
          "trap 'rm -f \"$tmp\"' EXIT",
          "printf '%s' '{{ images }}' | base64 -d > \"$tmp\"",
          "cd /tmp",
          "sudo -u deploy -H env SSH_ORIGINAL_COMMAND='deploy {{ sha }}' /opt/convoy/src/deploy/lite/deploy-ssh.sh < \"$tmp\"",
        ]
      }
    }]
  })
}

resource "aws_eip" "ip" {
  domain   = "vpc"
  instance = aws_instance.host.id
  tags     = { Name = "convoy-lite" }
  lifecycle { prevent_destroy = true } # DuckDNS points at this address
}

output "public_ip" { value = aws_eip.ip.public_ip }
output "instance_id" { value = aws_instance.host.id }
output "shell" { value = "aws ssm start-session --profile convoy-aws --region ap-southeast-2 --target ${aws_instance.host.id}" }
