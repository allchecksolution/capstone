resource "aws_iam_policy" "recordings" {
  name        = "${var.bucket_name}-recordings"
  description = "Backend verified recording upload and authorized original access."
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RecordingObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.private_media.arn}/recordings/*"
      },
      {
        # S3 returns 403 instead of 404 for a missing HEAD object without ListBucket.
        # This supports distinguishing an unfinished upload (400) from permission/service failure (503).
        Sid      = "DistinguishMissingRecording"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.private_media.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "recordings" {
  count      = var.ec2_role_name == null ? 0 : 1
  role       = var.ec2_role_name
  policy_arn = aws_iam_policy.recordings.arn
}
