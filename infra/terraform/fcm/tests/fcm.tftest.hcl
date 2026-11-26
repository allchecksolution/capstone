mock_provider "google" {}

variables {
  project_id = "allcheck-fcm-test"
}

run "restricted_sender_defaults" {
  command = plan

  assert {
    condition     = toset(google_project_iam_custom_role.sender.permissions) == toset(["cloudmessaging.messages.create"])
    error_message = "The runtime sender must not gain project administration or topic-management permissions."
  }
  assert {
    condition     = length(google_service_account_iam_member.developer) == 0
    error_message = "No developer may impersonate the sender without an explicit input."
  }
  assert {
    condition     = alltrue([for service in google_project_service.required : !service.disable_on_destroy])
    error_message = "Removing this scaffold must not disable shared project APIs."
  }
  assert {
    condition     = output.backend_environment.FCM_PROJECT_ID == var.project_id && length(keys(output.backend_environment)) == 2
    error_message = "Backend outputs must contain the intended project and no credentials."
  }
}

run "explicit_developer_access" {
  command = plan
  variables {
    developer_members = ["user:developer@example.com"]
  }

  assert {
    condition     = length(google_service_account_iam_member.developer) == 1 && google_service_account_iam_member.developer["user:developer@example.com"].role == "roles/iam.serviceAccountTokenCreator"
    error_message = "Only the listed developer may receive sender impersonation access."
  }
}

run "reject_public_impersonation" {
  command = plan
  variables {
    developer_members = ["allUsers"]
  }
  expect_failures = [var.developer_members]
}
