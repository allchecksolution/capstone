locals {
  oidc_issuer       = "token.actions.githubusercontent.com"
  github_subject    = coalesce(var.github_oidc_subject, "repo:${var.github_repository}:environment:${var.environment}")
  oidc_provider_arn = var.github_oidc_provider_arn != null ? var.github_oidc_provider_arn : aws_iam_openid_connect_provider.github[0].arn
}

resource "aws_secretsmanager_secret" "runtime" {
  for_each = toset(["backend/runtime", "ai/runtime", "ghcr/pull"])
  name     = "allcheck/${var.environment}/${each.key}"
}

resource "aws_iam_role" "instance" {
  name = "${local.name}-ec2"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_instance_profile" "instance" {
  name = aws_iam_role.instance.name
  role = aws_iam_role.instance.name
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "runtime" {
  name = "read-runtime-secrets"
  role = aws_iam_role.instance.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [for secret in aws_secretsmanager_secret.runtime : secret.arn]
    }]
  })
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.github_oidc_provider_arn == null ? 1 : 0
  url            = "https://${local.oidc_issuer}"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github" {
  name = "${local.name}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_issuer}:aud" = "sts.amazonaws.com"
          "${local.oidc_issuer}:sub" = local.github_subject
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github" {
  name = "deploy-with-ssm"
  role = aws_iam_role.github.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = ["arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript", aws_instance.app_data.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:DescribeInstanceInformation", "ssm:GetCommandInvocation"]
        Resource = "*"
        Condition = {
          StringEquals = { "aws:RequestedRegion" = var.aws_region }
        }
      }
    ]
  })
}
