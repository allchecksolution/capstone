output "bucket_name" {
  description = "Private media bucket."
  value       = aws_s3_bucket.private_media.id
}

output "bucket_arn" {
  value = aws_s3_bucket.private_media.arn
}

output "recordings_policy_arn" {
  description = "Attach to the Backend execution role if backend_role_name was not specified."
  value       = aws_iam_policy.recordings.arn
}

output "backend_environment" {
  description = "Non-secret Backend configuration. S3_REQUEST_TIMEOUT must be supplied separately."
  value = {
    S3_ENABLED                 = "true"
    S3_REGION                  = var.aws_region
    RECEPTION_RECORDING_BUCKET = aws_s3_bucket.private_media.id
  }
}
