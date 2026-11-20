# Firebase project creation and application registration happen in the console.
# These APIs support provisioning, FCM HTTP v1 and keyless local impersonation.
resource "google_project_service" "required" {
  for_each = toset([
    "cloudresourcemanager.googleapis.com",
    "fcm.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_service_account" "sender" {
  project      = var.project_id
  account_id   = var.sender_account_id
  display_name = "ALLCHECK development FCM sender"
  description  = "Local backend FCM HTTP v1 sender; uses ADC, without a generated private key."

  depends_on = [google_project_service.required]
}
