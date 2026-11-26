# HTTP v1 token delivery requires only message creation, not Firebase administration.
resource "google_project_iam_custom_role" "sender" {
  project     = var.project_id
  role_id     = "allcheckFcmSender"
  title       = "ALLCHECK FCM sender"
  description = "Send FCM HTTP v1 messages without topic or project administration."
  permissions = ["cloudmessaging.messages.create"]

  depends_on = [google_project_service.required]
}

# Member resources preserve other principals' existing IAM grants.
resource "google_project_iam_member" "sender" {
  project = var.project_id
  role    = google_project_iam_custom_role.sender.name
  member  = "serviceAccount:${google_service_account.sender.email}"
}

resource "google_service_account_iam_member" "developer" {
  for_each = var.developer_members

  service_account_id = google_service_account.sender.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = each.value
}
