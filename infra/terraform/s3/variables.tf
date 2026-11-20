variable "aws_account_id" {
  description = "Explicit target AWS account. The provider refuses a different account."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "Specify the intended 12-digit AWS account ID."
  }
}

variable "aws_profile" {
  description = "Optional local AWS profile; leave null for deployment-role/default-chain credentials."
  type        = string
  default     = null
}

variable "aws_region" {
  description = "Region for the bucket and Backend S3 client."
  type        = string
  validation {
    condition     = length(trimspace(var.aws_region)) > 0
    error_message = "Specify the intended AWS region."
  }
}

variable "environment" {
  description = "Deployment environment tag."
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be dev, staging, or prod."
  }
}

variable "bucket_name" {
  description = "Globally unique private media bucket name. No personal information in the name."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name)) && !strcontains(var.bucket_name, "..")
    error_message = "Use a valid 3-to-63-character lowercase S3 bucket name."
  }
}

variable "backend_role_name" {
  description = "Existing Backend execution-role name to receive recording permissions. Null creates the policy without attaching it."
  type        = string
  default     = null
  validation {
    condition     = var.backend_role_name == null ? true : length(trimspace(var.backend_role_name)) > 0
    error_message = "Backend role name must be nonblank when specified."
  }
}

variable "allowed_cors_origins" {
  description = "Explicit browser origins if web upload/playback is used. Native apps need no CORS. No rule is created when empty."
  type        = set(string)
  default     = []
  validation {
    condition = alltrue([
      for origin in var.allowed_cors_origins :
      can(regex("^https://[^/?#*]+$", origin)) || can(regex("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?$", origin))
    ])
    error_message = "Use explicit HTTPS origins, or HTTP loopback origins for local development; no wildcard or path."
  }
}

variable "tags" {
  description = "Additional non-secret resource tags."
  type        = map(string)
  default     = {}
}
