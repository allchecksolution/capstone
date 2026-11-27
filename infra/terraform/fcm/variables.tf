variable "project_id" {
  description = "Development Firebase project ID, created in the Firebase console first. Not the display name or project number."
  type        = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "Specify the intended 6-to-30-character Firebase project ID."
  }
}

variable "sender_account_id" {
  description = "Dedicated sender service-account ID within the project. No private key is generated."
  type        = string
  default     = "allcheck-dev-fcm"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.sender_account_id))
    error_message = "Use a valid 6-to-30-character service-account ID."
  }
}

variable "developer_members" {
  description = "Explicit user:email or group:email principals allowed to impersonate this sender. Empty grants no developer access."
  type        = set(string)
  default     = []
  validation {
    condition = alltrue([
      for member in var.developer_members :
      can(regex("^(user|group):[^@[:space:]]+@[^@[:space:]]+$", member))
    ])
    error_message = "Specify explicit user:email or group:email members; public principals are forbidden."
  }
}

variable "staging_wif" {
  description = "Optional AWS role allowed to impersonate the existing sender through a dedicated WIF pool."
  type = object({
    project_number = string
    aws_account_id = string
    aws_role_name  = string
    pool_id        = optional(string, "allcheck-staging-fcm")
    provider_id    = optional(string, "aws-ec2")
  })
  default = null

  validation {
    condition = var.staging_wif == null ? true : (
      can(regex("^[0-9]+$", var.staging_wif.project_number)) &&
      can(regex("^[0-9]{12}$", var.staging_wif.aws_account_id)) &&
      can(regex("^[A-Za-z0-9_+=,.@-]{1,64}$", var.staging_wif.aws_role_name)) &&
      can(regex("^[a-z][a-z0-9-]{2,30}[a-z0-9]$", var.staging_wif.pool_id)) &&
      can(regex("^[a-z][a-z0-9-]{2,30}[a-z0-9]$", var.staging_wif.provider_id)) &&
      !startswith(var.staging_wif.pool_id, "gcp-") &&
      !startswith(var.staging_wif.provider_id, "gcp-")
    )
    error_message = "Specify a numeric project number, 12-digit AWS account, exact IAM role name, and valid 4-to-32-character WIF IDs without the reserved gcp- prefix."
  }
}
