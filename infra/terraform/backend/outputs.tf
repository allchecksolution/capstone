output "public_ip" {
  description = "Elastic IP for the NPM proxy and DNS records."
  value       = aws_eip.public.public_ip
}

output "ec2_role_name" {
  description = "Pass this to the separate S3 root as backend_role_name when enabling media."
  value       = aws_iam_role.instance.name
}

output "github_oidc_provider_arn" {
  description = "Reuse this ARN when applying an additional environment in the same AWS account."
  value       = local.oidc_provider_arn
}

output "github_environment_variables" {
  value = {
    AWS_REGION                 = var.aws_region
    AWS_ROLE_ARN               = aws_iam_role.github.arn
    BACKEND_INSTANCE_ID        = aws_instance.app_data.id
    BACKEND_RUNTIME_SECRET_ARN = aws_secretsmanager_secret.runtime["backend/runtime"].arn
  }
}

output "optional_runtime_secret_arns" {
  description = "Register the corresponding GitHub variables after putting secret values in AWS."
  value = {
    AI_RUNTIME_SECRET_ARN        = aws_secretsmanager_secret.runtime["ai/runtime"].arn
    BACKEND_GHCR_PULL_SECRET_ARN = aws_secretsmanager_secret.runtime["ghcr/pull"].arn
  }
}
