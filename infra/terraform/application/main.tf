locals {
  name     = "allcheck-${var.environment}"
  repo_dir = "${path.module}/../.."
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_vpc" "deployment" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = local.name }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.deployment.id
  availability_zone       = var.availability_zone
  cidr_block              = "10.42.1.0/24"
  map_public_ip_on_launch = true
  tags                    = { Name = "${local.name}-public" }
}

resource "aws_internet_gateway" "public" {
  vpc_id = aws_vpc.deployment.id
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.deployment.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.public.id
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "instance" {
  name        = "${local.name}-web"
  description = "Public access to the Nginx Proxy Manager HTTP and HTTPS listeners"
  vpc_id      = aws_vpc.deployment.id
}

resource "aws_vpc_security_group_ingress_rule" "web" {
  for_each          = toset(["80", "443"])
  security_group_id = aws_security_group.instance.id
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "outbound" {
  security_group_id = aws_security_group.instance.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_ebs_volume" "data" {
  availability_zone = var.availability_zone
  type              = "gp3"
  size              = var.data_volume_size_gib
  encrypted         = true
  tags              = { Name = "${local.name}-data" }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_instance" "app_data" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.instance.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = var.enable_media_access ? 2 : 1
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
    volume_size = 30
  }

  user_data = templatefile("${local.repo_dir}/user-data/app-data.sh.tftpl", {
    volume_serial = replace(aws_ebs_volume.data.id, "-", "")
    app_compose   = filebase64("${local.repo_dir}/compose/app-data.docker-compose.yml")
    edge_compose  = filebase64("${local.repo_dir}/compose/edge.docker-compose.yml")
    postgres_init = filebase64("${local.repo_dir}/scripts/init-postgres.sh")
  })
  tags = { Name = "${local.name}-app-data" }

  # Bootstrap is applied to new hosts. CD sends updated app and edge Compose files.
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  depends_on = [aws_route_table_association.public, aws_iam_role_policy_attachment.ssm]
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app_data.id
}

resource "aws_eip" "public" {
  domain = "vpc"
  tags   = { Name = "${local.name}-public" }
}

resource "aws_eip_association" "public" {
  allocation_id = aws_eip.public.id
  instance_id   = aws_instance.app_data.id
}
