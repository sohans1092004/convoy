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

variable "admin_cidr" {
  description = "Your IP for SSH (x.x.x.x/32)"
  type        = string
}

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
  ingress {
    description = "SSH, admin only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
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

resource "aws_instance" "host" {
  ami                    = data.aws_ssm_parameter.ubuntu.value
  instance_type          = "t4g.medium" # 2 vCPU, 4 GiB: the lite stack idles at ~2.3 GiB (reports/p6/memory-laptop.md)
  subnet_id              = aws_subnet.s.id
  vpc_security_group_ids = [aws_security_group.host.id]
  key_name               = aws_key_pair.admin.key_name
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

resource "aws_eip" "ip" {
  domain   = "vpc"
  instance = aws_instance.host.id
  tags     = { Name = "convoy-lite" }
  lifecycle { prevent_destroy = true } # DuckDNS points at this address
}

output "public_ip" { value = aws_eip.ip.public_ip }
output "ssh" { value = "ssh -i ~/.ssh/convoy-ec2 ubuntu@${aws_eip.ip.public_ip}" }
