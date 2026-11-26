#!/usr/bin/env bash
set -euo pipefail

: "${AWS_REGION:?Set the deployment region}" "${GITHUB_REPOSITORY:?Set the image repository}"
: "${BACKEND_INSTANCE_ID:?Set the EC2 instance ID}" "${DEPLOY_TARGET:?Select the services to deploy}"

case "$DEPLOY_TARGET" in
  backend|ai|both) ;;
  *) echo "Deploy target must be backend, ai, or both." >&2; exit 1 ;;
esac

image_version='^[a-f0-9]{40}@sha256:[a-f0-9]{64}$'
repository=$(printf '%s' "$GITHUB_REPOSITORY" | tr '[:upper:]' '[:lower:]')
backend_image=
ai_image=
backend_secret=
ai_secret=
if [[ "$DEPLOY_TARGET" != ai ]]; then
  if [[ ! "${IMAGE_REVISION:-}" =~ $image_version ]]; then
    echo "Pass the backend SHA and digest from the CD workflow summary." >&2
    exit 1
  fi
  backend_image="ghcr.io/$repository-backend:$IMAGE_REVISION"
  backend_secret=${BACKEND_RUNTIME_SECRET_ARN:?Set the backend runtime secret ARN}
fi
if [[ "$DEPLOY_TARGET" != backend ]]; then
  if [[ ! "${AI_IMAGE_REVISION:-}" =~ $image_version ]]; then
    echo "Pass the AI SHA and digest from the CD workflow summary." >&2
    exit 1
  fi
  ai_image="ghcr.io/$repository-ai:$AI_IMAGE_REVISION"
  ai_secret=${AI_RUNTIME_SECRET_ARN:-}
fi
if [[ ! "$BACKEND_INSTANCE_ID" =~ ^i-([a-f0-9]{8}|[a-f0-9]{17})$ ]]; then
  echo "BACKEND_INSTANCE_ID must identify exactly one EC2 instance." >&2
  exit 1
fi

pull_secret=${BACKEND_GHCR_PULL_SECRET_ARN:-}
secret_arn="^arn:aws:secretsmanager:$AWS_REGION:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+$"
for arn in "$backend_secret" "$pull_secret" "$ai_secret"; do
  if [[ -n "$arn" && ! "$arn" =~ $secret_arn ]]; then
    echo "Use Secrets Manager ARNs in the deployment region." >&2
    exit 1
  fi
done

export AWS_MAX_ATTEMPTS=2
aws_cli=(aws --region "$AWS_REGION" --cli-connect-timeout 5 --cli-read-timeout 10 --no-cli-pager)
ping_status=$("${aws_cli[@]}" ssm describe-instance-information \
  --filters "Key=InstanceIds,Values=$BACKEND_INSTANCE_ID" \
  --query 'InstanceInformationList[0].PingStatus' --output text)
if [[ "$ping_status" != Online ]]; then
  echo "The selected EC2 instance is not online in Systems Manager." >&2
  exit 1
fi

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
bundle=$(tar -czf - \
  -C "$repo/infra/scripts" deploy.sh init-postgres.sh \
  -C "$repo/infra/compose" app-data.docker-compose.yml edge.docker-compose.yml | base64 | tr -d '\n')
# Create and expand bundle_dir on EC2.
# shellcheck disable=SC2016
printf -v remote_command 'set -eu
umask 077
bundle_dir=$(mktemp -d /run/allcheck-cd.XXXXXX)
trap '\''rm -rf -- "$bundle_dir"'\'' EXIT
printf %%s %q | base64 --decode | tar -xz -C "$bundle_dir"
bash "$bundle_dir/deploy.sh" %q %q %q %q %q %q %q' \
  "$bundle" "$DEPLOY_TARGET" "$backend_image" "$ai_image" "$AWS_REGION" "$backend_secret" "$ai_secret" "$pull_secret"
parameters=$(jq -cn --arg command "$remote_command" '{commands:[$command],executionTimeout:["600"]}')
command_id=$("${aws_cli[@]}" ssm send-command --document-name AWS-RunShellScript \
  --instance-ids "$BACKEND_INSTANCE_ID" --parameters "$parameters" --timeout-seconds 90 \
  --comment "Deploy ALLCHECK $DEPLOY_TARGET" --query Command.CommandId --output text)
if [[ ! "$command_id" =~ ^[a-f0-9-]{36}$ ]]; then
  echo "Systems Manager did not return a command ID." >&2
  exit 1
fi
echo "SSM command $command_id for $BACKEND_INSTANCE_ID"

deadline=$((SECONDS + 720))
while (( SECONDS < deadline )); do
  if status=$("${aws_cli[@]}" ssm get-command-invocation --command-id "$command_id" \
    --instance-id "$BACKEND_INSTANCE_ID" --query Status --output text 2>&1); then
    case "$status" in
      Success)
        echo "Deployment and EC2 application readiness checks succeeded."
        if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
          {
            printf 'Deployed %s to \x60%s\x60 (SSM command \x60%s\x60).\n\n' "$DEPLOY_TARGET" "$BACKEND_INSTANCE_ID" "$command_id"
            for image in "$backend_image" "$ai_image"; do
              if [[ -n "$image" ]]; then
                printf -- '- \x60%s\x60\n' "$image"
              fi
            done
          } >> "$GITHUB_STEP_SUMMARY"
        fi
        exit 0
        ;;
      Pending|InProgress|Delayed|Cancelling) ;;
      *) echo "SSM command ended with status $status. See Run Command $command_id." >&2; exit 1 ;;
    esac
  elif [[ "$status" != *InvocationDoesNotExist* ]]; then
    printf 'Unable to read SSM command %s status.\n%s\n' "$command_id" "$status" >&2
    exit 1
  fi
  sleep 5
done

echo "Timed out waiting for Run Command $command_id. Check its final state in Systems Manager." >&2
exit 1
