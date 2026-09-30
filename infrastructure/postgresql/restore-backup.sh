#!/bin/sh
# Runs ONLY when PostgreSQL initialises an empty data directory (mounted into /docker-entrypoint-initdb.d).
# If an automatic backup exists (see wait-for-credentials.sh), load it so users/settings are not lost.
BACKUP=/backups/orbit.sql
if [ -s "$BACKUP" ]; then
  echo "[orbit-postgres] empty data directory - restoring the database from $BACKUP"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "$BACKUP"
  echo "[orbit-postgres] restore finished"
else
  echo "[orbit-postgres] empty data directory and no backup found - starting with a new, empty database"
fi
