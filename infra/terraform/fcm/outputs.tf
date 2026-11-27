output "project_id" {
  value = var.project_id
}

output "sender_service_account_email" {
  description = "Target for local ADC impersonation, not a credential or private key."
  value       = google_service_account.sender.email
}

output "backend_environment" {
  description = "Non-secret settings; provide local ADC and HTTP timeouts separately."
  value = {
    FCM_ENABLED    = "true"
    FCM_PROJECT_ID = var.project_id
  }
}

output "staging_wif_provider_name" {
  description = "Full provider name for gcloud create-cred-config; null when WIF is disabled."
  value       = try(google_iam_workload_identity_pool_provider.aws["staging"].name, null)
}
