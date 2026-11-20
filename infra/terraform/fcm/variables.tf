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
