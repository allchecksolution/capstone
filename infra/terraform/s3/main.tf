resource "aws_s3_bucket" "private_media" {
  bucket        = var.bucket_name
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "private_media" {
  bucket = aws_s3_bucket.private_media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "private_media" {
  bucket = aws_s3_bucket.private_media.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "private_media" {
  bucket = aws_s3_bucket.private_media.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "private_media" {
  bucket = aws_s3_bucket.private_media.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.private_media.arn, "${aws_s3_bucket.private_media.arn}/*"]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
      {
        Sid       = "DenyUnconditionalRecordingWrites"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.private_media.arn}/recordings/*"
        Condition = {
          Null = { "s3:if-none-match" = "true" }
          Bool = { "s3:ObjectCreationOperation" = "true" }
        }
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.private_media]
}

resource "aws_s3_bucket_cors_configuration" "private_media" {
  count  = length(var.allowed_cors_origins) > 0 ? 1 : 0
  bucket = aws_s3_bucket.private_media.id

  cors_rule {
    allowed_origins = sort(tolist(var.allowed_cors_origins))
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_headers = ["Content-Type", "Content-Length", "If-None-Match", "If-Match", "Range", "x-amz-checksum-sha256", "x-amz-sdk-checksum-algorithm"]
    expose_headers  = ["ETag", "x-amz-checksum-sha256", "Content-Length", "Content-Range", "Accept-Ranges"]
    max_age_seconds = 600
  }
}
