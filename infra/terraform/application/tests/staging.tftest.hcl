mock_provider "aws" {
  mock_data "aws_ssm_parameter" {
    defaults = {
      value = "ami-0123456789abcdef0"
    }
  }
}

variables {
  aws_account_id    = "123456789012"
  aws_region        = "ap-northeast-2"
  availability_zone = "ap-northeast-2a"
  environment       = "staging"
}

run "single_instance_staging" {
  command = apply

  assert {
    condition     = aws_instance.app_data.instance_type == "t3.medium" && aws_ebs_volume.data.encrypted
    error_message = "Staging must default to t3.medium and use encrypted app data storage."
  }

  assert {
    condition     = toset(keys(aws_vpc_security_group_ingress_rule.web)) == toset(["80", "443"])
    error_message = "Public ingress must only contain NPM HTTP and HTTPS."
  }

  assert {
    condition     = aws_instance.app_data.metadata_options[0].http_tokens == "required" && aws_instance.app_data.metadata_options[0].http_put_response_hop_limit == 1
    error_message = "Require IMDSv2 and keep container credentials off before enabling media."
  }

  assert {
    condition     = !strcontains(aws_iam_role_policy.github.policy, "secretsmanager:")
    error_message = "The GitHub deployment role must not read application secrets."
  }
}

run "enable_media" {
  command = plan

  variables {
    enable_media_access = true
  }

  assert {
    condition     = aws_instance.app_data.metadata_options[0].http_put_response_hop_limit == 2
    error_message = "Allow the backend in Docker to use the instance role when media is enabled."
  }
}
