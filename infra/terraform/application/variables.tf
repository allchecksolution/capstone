variable "aws_account_id" {
  description = "The intended AWS account ID."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "Provide a 12-digit AWS account ID."
  }
}

variable "aws_profile" {
  description = "Optional AWS profile for Terraform."
  type        = string
  default     = null
}

variable "aws_region" {
  description = "AWS Region for the environment."
  type        = string
}

variable "availability_zone" {
  description = "Availability Zone for both the instance and its data volume."
  type        = string
}

variable "environment" {
  description = "GitHub deployment environment."
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "Use staging or production."
  }
}

variable "instance_type" {
  description = "x86_64 Nitro instance type. The staging default provides 2 vCPUs and 4 GiB."
  type        = string
  default     = "t3.medium"
}

variable "data_volume_size_gib" {
  description = "Size of the persistent PostgreSQL and NPM gp3 volume."
  type        = number
  default     = 40
}

variable "enable_media_access" {
  description = "Allow the Dockerized backend to obtain the EC2 role credentials for S3. Apply with the media policy."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "GitHub repository in owner/name form."
  type        = string
  default     = "allchecksolution/capstone"
}

variable "github_oidc_provider_arn" {
  description = "Existing GitHub Actions OIDC provider ARN. With null this root creates the provider."
  type        = string
  default     = null
}

variable "github_oidc_subject" {
  description = "Exact trusted GitHub OIDC subject when the repository uses a custom or immutable-ID subject."
  type        = string
  default     = null
}
