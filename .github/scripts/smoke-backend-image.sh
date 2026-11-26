#!/usr/bin/env bash
set -euo pipefail

image=${1:?Pass the backend image to verify}
suffix=$(openssl rand -hex 6)
network=allcheck-smoke-$suffix
postgres=allcheck-smoke-db-$suffix
backend=allcheck-smoke-api-$suffix
password=$(openssl rand -hex 24)
jwt_key=$(openssl rand -base64 32)

cleanup() {
  docker rm -f "$backend" "$postgres" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run -d --name "$postgres" --network "$network" --platform linux/amd64 \
  -e POSTGRES_DB=allcheck -e POSTGRES_USER=allcheck -e "POSTGRES_PASSWORD=$password" \
  postgis/postgis:17-3.5 >/dev/null

deadline=$((SECONDS + 90))
until docker exec "$postgres" pg_isready -h 127.0.0.1 -U allcheck -d allcheck >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "Smoke test PostgreSQL did not start." >&2
    exit 1
  fi
  sleep 2
done

docker run -d --name "$backend" --network "$network" --platform linux/amd64 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://$postgres:5432/allcheck" \
  -e SPRING_DATASOURCE_USERNAME=allcheck -e "SPRING_DATASOURCE_PASSWORD=$password" \
  -e SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=3000 \
  -e "JWT_SECRET=$jwt_key" "$image" >/dev/null

readiness=http://127.0.0.1:8080/actuator/health/readiness
deadline=$((SECONDS + 150))
until docker exec "$backend" curl -fsS --max-time 4 "$readiness" 2>/dev/null |
  jq -e '.status == "UP"' >/dev/null; do
  if (( SECONDS >= deadline )) || [[ "$(docker inspect -f '{{.State.Running}}' "$backend")" != true ]]; then
    echo "Backend did not become ready with PostgreSQL." >&2
    docker logs "$backend" >&2
    exit 1
  fi
  sleep 2
done
echo "Backend is ready after running Flyway migrations against PostgreSQL with PostGIS."

docker stop "$postgres" >/dev/null
status=$(docker exec "$backend" curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$readiness")
if [[ "$status" != 503 ]]; then
  echo "Backend readiness must fail when PostgreSQL is unavailable (received HTTP $status)." >&2
  exit 1
fi
echo "Readiness correctly returns HTTP 503 without PostgreSQL."
