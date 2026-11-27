#!/usr/bin/env bash
set -euo pipefail
umask 077

target=${1:?Pass the services to deploy}
backend_release=${2:-}
ai_release=${3:-}
region=${4:?Pass the AWS region}
runtime_secret=${5:-}
ai_secret=${6:-}
pull_secret=${7:-}
bundle=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
data=/var/lib/allcheck
state_dir=$data/deployment
state=$state_dir/current.json

fail() {
  echo "$*" >&2
  exit 1
}

case "$target" in
  backend) services=(backend) ;;
  ai) services=(ai) ;;
  both) services=(backend ai) ;;
  *) fail "Deploy target must be backend, ai, or both." ;;
esac

mountpoint -q "$data" || fail "The persistent data volume is not mounted. Check EC2 bootstrap."
docker compose version >/dev/null
docker network inspect allcheck-proxy >/dev/null || fail "The allcheck-proxy Docker network is missing."
exec 9>/run/lock/allcheck-deploy.lock
flock -n 9 || fail "Another deployment is running."
install -d -m 0700 "$state_dir"
scratch=$(mktemp -d /run/allcheck-env.XXXXXX)
old="$scratch/previous"
deployment=$bundle
replacement_started=false
committed=false

compose_app() {
  local directory=$1
  shift
  docker compose --env-file /dev/null -p allcheck-app-data -f "$directory/app-data.docker-compose.yml" "$@"
}

verify_app() {
  if [[ "$target" != ai ]]; then
    compose_app "$1" exec -T backend curl -fsS --max-time 10 http://127.0.0.1:8080/actuator/health/readiness |
      jq -e '.status == "UP"' >/dev/null || return 1
  fi
  if [[ "$target" != backend ]]; then
    compose_app "$1" exec -T ai python -c 'import json, urllib.request; opener = urllib.request.build_opener(urllib.request.ProxyHandler({})); response = opener.open("http://127.0.0.1:8000/health", timeout=5); assert response.status == 200 and json.load(response) == {"status": "ok"}' || return 1
  fi
}

finish() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$replacement_started" == true && "$committed" == false ]]; then
    if [[ -f "$old/app-data.docker-compose.yml" ]]; then
      export BACKEND_IMAGE="$old_backend" AI_IMAGE="$old_ai"
      export ALLCHECK_BACKEND_ENV="$old/backend.env" ALLCHECK_AI_ENV="$old/ai.env"
      if compose_app "$old" up -d --no-deps --pull never --force-recreate --wait --wait-timeout 120 "${services[@]}" &&
        verify_app "$old"; then
        echo "Previous containers restored for target: $target."
      else
        echo "Previous application release could not be restored to readiness." >&2
      fi
    else
      compose_app "$deployment" rm -sf "${services[@]}" >/dev/null || echo "Failed to remove the unready application containers." >&2
    fi
  fi
  rm -rf -- "$scratch"
  exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

export AWS_PAGER=""
read_secret() {
  aws --region "$region" --cli-connect-timeout 5 --cli-read-timeout 10 \
    secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text
}

render_env() {
  jq -r '
    if type != "object" then error("Expected an environment object") else to_entries[] end
    | if (.key | test("^[A-Z_][A-Z0-9_]*$")) and
         (.value | type == "string" and (explode | all(. != 0 and . != 10 and . != 13)))
      then "\(.key)=\(.value)" else error("Invalid runtime environment entry") end
  ' "$1" > "$2" 2>/dev/null
}

export ALLCHECK_BACKEND_ENV="$scratch/backend.env" ALLCHECK_AI_ENV="$scratch/ai.env"
export ALLCHECK_POSTGRES_ENV="$scratch/postgres.env" ALLCHECK_DATA_DIR="$data"
touch "$ALLCHECK_BACKEND_ENV" "$ALLCHECK_AI_ENV" "$ALLCHECK_POSTGRES_ENV"

if [[ -f "$state" ]]; then
  mkdir "$old"
  jq -er '.compose' "$state" > "$old/app-data.docker-compose.yml"
  old_backend=$(jq -er '.backend' "$state")
  old_ai=$(jq -er '.ai' "$state")
  export BACKEND_IMAGE="$old_backend" AI_IMAGE="$old_ai"
  for service in backend ai; do
    container=$(compose_app "$old" ps --all --quiet "$service")
    [[ -n "$container" ]] || fail "Previous $service container is missing. Check deployment state before retrying."
    docker container inspect --format '{{json .Config.Env}}' "$container" |
      jq -er '.[]' > "$old/$service.env"
    cp "$old/$service.env" "$scratch/$service.env"
  done

  # Only a deployment of both apps can update the shared Compose definition.
  if [[ "$target" != both ]]; then
    deployment=$old
  fi
elif [[ "$target" != both ]]; then
  fail "The initial deployment requires both application images."
fi

if [[ "$target" != ai ]]; then
  : "${runtime_secret:?Pass the backend secret ARN}"
  read_secret "$runtime_secret" > "$scratch/runtime.json"
  if ! jq -e '
    [.JWT_SECRET, .SPRING_DATASOURCE_URL, .SPRING_DATASOURCE_USERNAME, .SPRING_DATASOURCE_PASSWORD]
    | all(type == "string" and test("\\S"))
  ' "$scratch/runtime.json" >/dev/null 2>&1 ||
    ! jq -e '.SPRING_DATASOURCE_USERNAME | test("^[a-z_][a-z0-9_]{0,62}$") and . != "postgres"' "$scratch/runtime.json" >/dev/null 2>&1; then
    fail "Provide the JWT and application database login in the backend secret."
  fi
  jq 'del(.POSTGRES_ADMIN_PASSWORD, .SPRING_PROFILES_ACTIVE)' "$scratch/runtime.json" > "$scratch/backend.json"
  render_env "$scratch/backend.json" "$ALLCHECK_BACKEND_ENV" ||
    fail "The backend secret must use uppercase environment names and single-line string values."

  if [[ ! -f "$state" ]]; then
    jq -e '.POSTGRES_ADMIN_PASSWORD | type == "string" and test("\\S")' "$scratch/runtime.json" >/dev/null 2>&1 ||
      fail "Provide the separate PostgreSQL admin password for the initial deployment."
    jq '{
      POSTGRES_PASSWORD: .POSTGRES_ADMIN_PASSWORD,
      ALLCHECK_DB_USERNAME: .SPRING_DATASOURCE_USERNAME,
      ALLCHECK_DB_PASSWORD: .SPRING_DATASOURCE_PASSWORD
    }' "$scratch/runtime.json" > "$scratch/postgres.json"
    render_env "$scratch/postgres.json" "$ALLCHECK_POSTGRES_ENV" ||
      fail "The PostgreSQL bootstrap credentials must be single-line strings."
  fi

  if [[ "$(jq -r '(.FCM_ENABLED // "false") | ascii_downcase' "$scratch/runtime.json")" == true ]]; then
    [[ "$(jq -r '.GOOGLE_APPLICATION_CREDENTIALS' "$scratch/runtime.json")" == "/run/allcheck/google-adc.json" &&
      -f /etc/allcheck/backend/google-adc.json ]] || fail "Install the Google ADC file on EC2 before enabling FCM."
  fi
fi

if [[ "$target" != backend ]]; then
  if [[ -n "$ai_secret" ]]; then
    read_secret "$ai_secret" > "$scratch/ai.json"
    render_env "$scratch/ai.json" "$ALLCHECK_AI_ENV" ||
      fail "The AI secret must use uppercase environment names and single-line string values."
  else
    : > "$ALLCHECK_AI_ENV"
  fi
fi

env_value() {
  awk -v name="$2" 'index($0, name "=") == 1 { print substr($0, length(name) + 2); exit }' "$1"
}

if [[ "$(env_value "$ALLCHECK_BACKEND_ENV" RECEPTION_AI_ENABLED)" =~ ^[Tt][Rr][Uu][Ee]$ ]]; then
  backend_ai_key=$(env_value "$ALLCHECK_BACKEND_ENV" RECEPTION_AI_INTERNAL_API_KEY)
  [[ -n "$backend_ai_key" && "$backend_ai_key" == "$(env_value "$ALLCHECK_AI_ENV" AI_INTERNAL_API_KEY)" ]] ||
    fail "The backend and AI internal keys must match."
fi

export DOCKER_CONFIG="$scratch/docker"
mkdir "$DOCKER_CONFIG"
if [[ -n "$pull_secret" ]]; then
  read_secret "$pull_secret" > "$scratch/ghcr.json"
  username=$(jq -er '.username | select(type == "string" and test("^[A-Za-z0-9-]+$"))' "$scratch/ghcr.json" 2>/dev/null) ||
    fail "Invalid GHCR pull username."
  if ! jq -er '.token | select(type == "string" and test("^[A-Za-z0-9_]+$"))' "$scratch/ghcr.json" 2>/dev/null |
    docker login ghcr.io --username "$username" --password-stdin >/dev/null; then
    fail "GHCR pull authentication failed."
  fi
fi

pull_release() {
  local release=$1 component=$2 digest revision
  local pattern="^(ghcr\.io/[a-z0-9._-]+/[a-z0-9._-]+-$component):([a-f0-9]{40})@(sha256:[a-f0-9]{64})$"
  [[ "$release" =~ $pattern ]] || fail "Invalid $component image reference."
  digest="${BASH_REMATCH[1]}@${BASH_REMATCH[3]}"
  revision=${BASH_REMATCH[2]}
  docker pull "$digest" >&2
  [[ "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$digest")" == "$revision" ]] ||
    fail "The $component image revision label does not match the requested commit."
  printf '%s\n' "$digest"
}

if [[ "$target" != ai ]]; then
  BACKEND_IMAGE=$(pull_release "$backend_release" backend)
fi
if [[ "$target" != backend ]]; then
  AI_IMAGE=$(pull_release "$ai_release" ai)
fi
export BACKEND_IMAGE AI_IMAGE
compose_app "$deployment" config --quiet

if [[ ! -f "$state" ]]; then
  install -m 0755 "$bundle/init-postgres.sh" /opt/allcheck/compose/init-postgres.sh
  docker compose --env-file /dev/null -p allcheck-edge -f "$bundle/edge.docker-compose.yml" up -d --wait --wait-timeout 60
  compose_app "$deployment" up -d --no-recreate --wait --wait-timeout 90 postgres
fi

replacement_started=true
compose_app "$deployment" up -d --no-deps --pull never --force-recreate --wait --wait-timeout 150 "${services[@]}"
verify_app "$deployment" || fail "The new deployment did not reach readiness for target: $target."

updated_state=$(mktemp "$state_dir/.current.XXXXXX")
jq -cn --arg backend "$BACKEND_IMAGE" --arg ai "$AI_IMAGE" --rawfile compose "$deployment/app-data.docker-compose.yml" \
  '{backend: $backend, ai: $ai, compose: $compose}' > "$updated_state"
mv -f -- "$updated_state" "$state"
committed=true
printf 'Ready deployment target: %s\n' "$target"
