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
  assert {
    condition     = length(google_iam_workload_identity_pool.staging) == 0 && length(google_project_service.sts) == 0
    error_message = "WIF must remain opt-in for existing local-only configurations."
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

run "restricted_staging_wif" {
  command = plan

  variables {
    developer_members = ["user:developer@example.com"]
    staging_wif = {
      project_number = "414145711645"
      aws_account_id = "303357410104"
      aws_role_name  = "allcheck-staging-ec2"
    }
  }

  override_data {
    target = data.google_project.wif["staging"]
    values = { number = "414145711645" }
  }

  assert {
    condition     = google_project_service.sts["staging"].service == "sts.googleapis.com" && !google_project_service.sts["staging"].disable_on_destroy
    error_message = "WIF must enable STS without disabling it on destroy."
  }
  assert {
    condition = (
      google_iam_workload_identity_pool_provider.aws["staging"].aws[0].account_id == "303357410104" &&
      google_iam_workload_identity_pool_provider.aws["staging"].attribute_mapping["attribute.aws_role"] == "assertion.arn.extract('assumed-role/{role_name}/')" &&
      google_iam_workload_identity_pool_provider.aws["staging"].attribute_condition == "assertion.account == '303357410104' && assertion.arn.startsWith('arn:aws:sts::303357410104:assumed-role/allcheck-staging-ec2/') && attribute.aws_role == 'allcheck-staging-ec2'"
    )
    error_message = "The provider must restrict both AWS account and exact assumed role, including the slash after the role name."
  }
  assert {
    condition = (
      google_service_account_iam_member.staging_wif["staging"].role == "roles/iam.workloadIdentityUser" &&
      google_service_account_iam_member.staging_wif["staging"].member == "principalSet://iam.googleapis.com/projects/414145711645/locations/global/workloadIdentityPools/allcheck-staging-fcm/attribute.aws_role/allcheck-staging-ec2" &&
      length(google_service_account_iam_member.developer) == 1 &&
      toset(google_project_iam_custom_role.sender.permissions) == toset(["cloudmessaging.messages.create"])
    )
    error_message = "WIF must grant only the intended role access while preserving developer and sender permissions."
  }
}

run "reject_mismatched_project_number" {
  command = plan
  variables {
    staging_wif = {
      project_number = "414145711645"
      aws_account_id = "303357410104"
      aws_role_name  = "allcheck-staging-ec2"
    }
  }
  override_data {
    target = data.google_project.wif["staging"]
    values = { number = "999999999999" }
  }
  expect_failures = [data.google_project.wif["staging"]]
}

run "reject_wildcard_role" {
  command = plan
  variables {
    staging_wif = {
      project_number = "414145711645"
      aws_account_id = "303357410104"
      aws_role_name  = "*"
    }
  }
  expect_failures = [var.staging_wif]
}
