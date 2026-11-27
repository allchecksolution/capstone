#!/usr/bin/env bash
set -euo pipefail

: "${ALLCHECK_DB_USERNAME:?Set the application DB role}" "${ALLCHECK_DB_PASSWORD:?Set the application DB password}"
psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user ALLCHECK_DB_USERNAME
\getenv app_password ALLCHECK_DB_PASSWORD
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password') \gexec
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'app_user') \gexec
SQL
