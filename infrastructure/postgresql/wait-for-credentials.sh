#!/bin/sh
# ORBIT PostgreSQL entrypoint.
#
# The database username/password are chosen by the user in ORBIT's first-run setup screen, which saves them to the
# project's .env (DB_USERNAME, DB_PASSWORD). This container therefore idles until those two values exist, then starts
# PostgreSQL with them - so the database is created for exactly the credentials the user picked.
# DB_NAME (default: orbit) is read from the same file. The port is set by docker-compose (DB_PORT, default 5433).
#
# NOTE: PostgreSQL only applies these values when it initialises an EMPTY data directory. Later changes to .env do not
# alter an existing database (use `docker compose down -v` to start over).
set -eu

ENV_FILE="${ORBIT_ENV_FILE:-/orbit/.env}"

read_var() {
  [ -f "$ENV_FILE" ] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r'
}

echo "[orbit-postgres] waiting for DB_USERNAME and DB_PASSWORD in $ENV_FILE (enter them in the ORBIT setup screen)..."
while :; do
  DB_USERNAME="$(read_var DB_USERNAME)"
  DB_PASSWORD="$(read_var DB_PASSWORD)"
  if [ -n "$DB_USERNAME" ] && [ -n "$DB_PASSWORD" ]; then
    break
  fi
  sleep 2
done

DB_NAME="$(read_var DB_NAME)"
export POSTGRES_USER="$DB_USERNAME"
export POSTGRES_PASSWORD="$DB_PASSWORD"
export POSTGRES_DB="${DB_NAME:-orbit}"

echo "[orbit-postgres] credentials found - starting PostgreSQL (database '$POSTGRES_DB', user '$POSTGRES_USER')"
exec docker-entrypoint.sh postgres
