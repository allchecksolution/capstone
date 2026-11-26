mock_provider "aws" {}

variables {
  aws_account_id = "000000000000"
  aws_region     = "ap-northeast-2"
  environment    = "dev"
  bucket_name    = "allcheck-test-private-media-000000000000"
}

run "private_defaults" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_public_access_block.private_media.block_public_acls &&
      aws_s3_bucket_public_access_block.private_media.block_public_policy &&
      aws_s3_bucket_public_access_block.private_media.ignore_public_acls &&
      aws_s3_bucket_public_access_block.private_media.restrict_public_buckets
    )
    error_message = "All four public access blocks must remain enabled."
  }
  assert {
    condition     = one(aws_s3_bucket_ownership_controls.private_media.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "ACL-based ownership must remain disabled."
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.private_media.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Default S3 encryption must remain configured."
  }
  assert {
    condition     = !aws_s3_bucket.private_media.force_destroy && length(aws_s3_bucket_cors_configuration.private_media) == 0 && length(aws_iam_role_policy_attachment.recordings) == 0
    error_message = "No forced data deletion, guessed browser origin, or guessed execution-role attachment."
  }
}

run "recording_policy" {
  command = plan

  override_resource {
    target          = aws_s3_bucket.private_media
    override_during = plan
    values = {
      id  = "allcheck-test-private-media-000000000000"
      arn = "arn:aws:s3:::allcheck-test-private-media-000000000000"
    }
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.private_media.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "Non-TLS access must be explicitly denied."
  }
  assert {
    condition     = endswith(jsondecode(aws_s3_bucket_policy.private_media.policy).Statement[1].Resource, "/recordings/*") && jsondecode(aws_s3_bucket_policy.private_media.policy).Statement[1].Condition.Null["s3:if-none-match"] == "true"
    error_message = "Immutable recordings require conditional writes without imposing that policy on future domain prefixes."
  }
  assert {
    condition     = toset(jsondecode(aws_iam_policy.recordings.policy).Statement[0].Action) == toset(["s3:GetObject", "s3:PutObject"])
    error_message = "The recording object policy must not grant deletion, ACL, or bucket administration."
  }
  assert {
    condition     = output.backend_environment.RECEPTION_RECORDING_BUCKET == var.bucket_name && output.backend_environment.S3_REGION == var.aws_region
    error_message = "Terraform outputs must match the common SDK and reception bucket bindings."
  }
}

run "explicit_browser_and_role" {
  command = plan
  variables {
    allowed_cors_origins = ["https://admin.example.com"]
    ec2_role_name        = "test-application-ec2-role"
  }
  assert {
    condition     = length(aws_s3_bucket_cors_configuration.private_media) == 1 && one(aws_s3_bucket_cors_configuration.private_media[0].cors_rule).allowed_origins == toset(["https://admin.example.com"])
    error_message = "CORS must use only the explicitly supplied browser origin."
  }
  assert {
    condition     = aws_iam_role_policy_attachment.recordings[0].role == "test-application-ec2-role"
    error_message = "Attach only to the explicitly supplied existing role."
  }
}
