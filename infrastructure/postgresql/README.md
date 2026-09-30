# ORBIT PostgreSQL (local)

The database runs as the `orbit-postgres` container defined in the root `docker-compose.yml`.

* Each computer gets its **own** database: data lives in the local Docker named volume `orbit_pgdata`, which is never committed to git.
* `docker compose restart`, `docker compose down`, `docker compose up -d --force-recreate` and image upgrades **do not delete data**.
* `docker compose down -v` (or `docker volume rm orbit_pgdata`) removes the volume, but `wait-for-credentials.sh` keeps an SQL backup in `./backups/orbit.sql` (every ~20 s, previous copy in `orbit.sql.prev`) and `restore-backup.sh` reloads it when PostgreSQL starts on an empty data directory. To really start from zero, also delete `./backups`.
* Schema is created/updated by the backend through Flyway migrations on startup, so an empty fresh database is initialised automatically.
