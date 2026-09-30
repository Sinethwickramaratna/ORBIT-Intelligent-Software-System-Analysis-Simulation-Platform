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

# ---- safety net: automatic backup ------------------------------------------------------------------------------
# The data normally lives in the Docker volume `orbit_pgdata`. In case that volume is ever removed (`docker compose
# down -v`, "delete volumes" in Docker Desktop, `docker volume prune`, ...) a plain SQL dump of the database is kept in
# the project's ./backups folder (git-ignored) every few seconds. When PostgreSQL starts on an EMPTY data directory,
# /docker-entrypoint-initdb.d/10-restore-backup.sh loads that dump again, so users and settings come back.
BACKUP_DIR="${ORBIT_BACKUP_DIR:-/backups}"
BACKUP_FILE="$BACKUP_DIR/orbit.sql"
BACKUP_INTERVAL="${ORBIT_BACKUP_INTERVAL:-20}"

backup_loop() {
  set +e
  export PGPASSWORD="$POSTGRES_PASSWORD"
  mkdir -p "$BACKUP_DIR"
  while :; do
    sleep "$BACKUP_INTERVAL"
    pg_isready -q -h 127.0.0.1 -p 5432 || continue
    if pg_dump -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges \
         > "$BACKUP_FILE.tmp" 2>/dev/null && tail -n 5 "$BACKUP_FILE.tmp" | grep -q "PostgreSQL database dump complete"; then
      if cmp -s "$BACKUP_FILE.tmp" "$BACKUP_FILE"; then
        rm -f "$BACKUP_FILE.tmp"
      else
        [ -f "$BACKUP_FILE" ] && cp "$BACKUP_FILE" "$BACKUP_FILE.prev"
        mv -f "$BACKUP_FILE.tmp" "$BACKUP_FILE"
      fi
    else
      rm -f "$BACKUP_FILE.tmp"
    fi
  done
}
backup_loop &

echo "[orbit-postgres] credentials found - starting PostgreSQL (database '$POSTGRES_DB', user '$POSTGRES_USER')"
# JIT (LLVM) is not shipped in the slim image
exec docker-entrypoint.sh postgres -c jit=off
