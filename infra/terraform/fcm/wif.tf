locals {
  staging_wif = var.staging_wif == null ? {} : { staging = var.staging_wif }
}

# Keep the optional API independent of the existing sender dependencies.
resource "google_project_service" "sts" {
  for_each = local.staging_wif

  project            = var.project_id
  service            = "sts.googleapis.com"
  disable_on_destroy = false
}

data "google_project" "wif" {
  for_each   = local.staging_wif
  project_id = var.project_id

  lifecycle {
    postcondition {
      condition     = tostring(self.number) == each.value.project_number
      error_message = "The WIF project number must match the existing Firebase project."
    }
  }
}

resource "google_iam_workload_identity_pool" "staging" {
  for_each = local.staging_wif

  project                   = var.project_id
  workload_identity_pool_id = each.value.pool_id
  display_name              = "ALLCHECK staging FCM"
  description               = "AWS staging role federation to the existing FCM sender."

  depends_on = [google_project_service.sts, data.google_project.wif]
}

resource "google_iam_workload_identity_pool_provider" "aws" {
  for_each = local.staging_wif

  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.staging[each.key].workload_identity_pool_id
  workload_identity_pool_provider_id = each.value.provider_id
  display_name                       = "Staging EC2 role"

  aws {
    account_id = each.value.aws_account_id
  }

  attribute_mapping = {
    "google.subject"     = "assertion.arn"
    "attribute.account"  = "assertion.account"
    "attribute.aws_role" = "assertion.arn.extract('assumed-role/{role_name}/')"
  }
  attribute_condition = "assertion.account == '${each.value.aws_account_id}' && assertion.arn.startsWith('arn:aws:sts::${each.value.aws_account_id}:assumed-role/${each.value.aws_role_name}/') && attribute.aws_role == '${each.value.aws_role_name}'"
}

# Add one member, preserving developer impersonation and existing sender permissions.
resource "google_service_account_iam_member" "staging_wif" {
  for_each = local.staging_wif

  service_account_id = google_service_account.sender.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.wif[each.key].number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.staging[each.key].workload_identity_pool_id}/attribute.aws_role/${each.value.aws_role_name}"

  depends_on = [google_iam_workload_identity_pool_provider.aws]
}
