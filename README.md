# ORBIT — Intelligent Software System Analysis & Simulation Platform

Phase 1: **login system** (Next.js frontend · Spring Boot 4 backend · PostgreSQL in Docker).

```
Orbit/
├── docker-compose.yml         # postgres + backend + frontend
├── .env.example               # SAMPLE with empty secrets  →  copy to .env
├── .env                       # your own settings, written by the setup screen (git-ignored)
├── infrastructure/postgresql  # entrypoint script: Postgres starts once DB credentials exist
├── backend/                   # Spring Boot 4 (Java 17+), Maven wrapper, Dockerfile
├── frontend/                  # Next.js (App Router, TypeScript), Dockerfile
└── llm-service/  ml-service/  # reserved for later phases
```

## Run it (any computer, after cloning)

### Option A — everything in Docker (needs only Docker)

```bash
cp .env.example .env              # once (start.cmd / start.sh do this for you). A sample: the secrets in it are EMPTY
docker compose up -d --build      # postgres + backend + frontend
```

Open http://localhost:3000 and complete the first-run screens (below). The **Postgres container waits** until you have
entered the database username/password, then starts with exactly those credentials.

### Option B — local dev (hot reload)

Requirements: Docker, Java 17+, Node 20+.

```bash
cp .env.example .env
docker compose up -d postgres              # 1. database container (idles until you enter credentials)
cd backend  && ./mvnw spring-boot:run      # 2. API on http://localhost:8080   (Windows: mvnw.cmd)
cd frontend && npm install && npm run dev  # 3. UI  on http://localhost:3000
```

The backend reads/writes `../.env` (the repo-root file) by default. Override with `ORBIT_ENV_FILE`.

## First-run flow

1. **Theme** – pick a colour theme (Orbit Dark/Light, VS Code Dark+/Light+, Monokai, Solarized, High Contrast). It is
   remembered in your browser and copied to the `app_settings` table as soon as the database exists.
2. **Environment** – enter the values below. They are saved to `.env`:

   | `.env` key | asked in the UI? | notes |
   |---|---|---|
   | `JWT_SECRET` | yes | ≥ 32 chars, signs the login tokens |
   | `DB_USERNAME` | yes | letters, digits, `_` |
   | `DB_PASSWORD` | yes | ≥ 8 chars |
   | `DB_NAME` | no – default `orbit` | |
   | `DB_PORT` | no – default `5433` | host port of the container |

   Allowed characters in the secret/password: letters, digits and `!@%^&*()_+-=[]{};:,.<>?/~|` (no spaces, quotes, `$`, `#`, `\`), because the same file is read by Spring, Docker Compose and a shell script.
3. **Database** – PostgreSQL starts with your credentials; the backend connects and Flyway creates the tables.
   A progress screen shows this. If it fails (e.g. wrong credentials for an existing database) the reason is shown with a Retry button.
4. **Register** – no users exist yet, so you register a username + password (stored as an Argon2id hash).
5. **Sign in** – then the welcome screen with a **Log out** button.

The backend starts *without* a database (only `/api/setup/**` works; every other API answers `503 DATABASE_NOT_READY`) so the setup screen can run first.

## Database persistence

Data lives in the Docker named volume `orbit_pgdata` on *your* machine (never in git).
`docker compose down`, restarts, re-creation and image upgrades keep it. Only `docker compose down -v` wipes it.

> PostgreSQL applies `DB_USERNAME`/`DB_PASSWORD` only when it initialises an **empty** data volume. Changing them in
> `.env` later does not change an existing database — run `docker compose down -v` to start over with new credentials.

## Tables

| table | columns |
|---|---|
| `users` | `user_id` UUID PK, `user_name`, `password` (Argon2), `created_at`, `updated_at` |
| `refresh_token_table` | `token_id` UUID PK, `token`, `user_id` FK→users, `created_at`, `expired_at` |
| `app_settings` | `setting_key` PK, `setting_value`, `updated_at` (theme) |

## API

Public: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`,
`GET /api/setup/status`, `POST /api/setup/environment`, `POST /api/setup/database/retry`, `GET|PUT /api/settings/theme`.
Protected (`Authorization: Bearer <access token>`): `POST /api/auth/logout`, `GET /api/users`, `GET /api/users/me`,
`GET|PUT|DELETE /api/users/{id}` (update/delete: own account only).

`POST /api/setup/environment` accepts `{secretKey?, dbUsername?, dbPassword?}` and only the values that are still
missing; anything already configured is rejected with `409 ENVIRONMENT_ALREADY_CONFIGURED`.

Errors are JSON: `{timestamp,status,code,message,path,fieldErrors?}` — e.g. `USER_NOT_FOUND`, `INVALID_PASSWORD`,
`TOKEN_EXPIRED`, `REFRESH_TOKEN_EXPIRED`, `DATABASE_NOT_READY`.

## Token design

* **Access token** – HS256 JWT, **15 minutes**, claims `user_id`, `username`, `exp` (+ `sub`, `iat`).
* **Refresh token** – opaque random 256-bit value, **1 day**, stored in `refresh_token_table` as a SHA-256 hash. Deleted on logout, when found expired, and by an hourly sweep.
* When the access token expires the frontend calls `/api/auth/refresh` and retries automatically.
* Spring Security chain: stateless; `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`; login goes through `AuthenticationManager` → `DaoAuthenticationProvider` → `OrbitUserDetailsService` (checks the username exists) + Argon2 password check.

## Tests

`cd backend && ./mvnw test` — unit tests plus an end-to-end auth flow test on in-memory H2 (no Docker needed).

## Security notes

`/api/setup/**` is unauthenticated by design (there are no users yet) and can only *set* values that are still empty. Keep the ports bound to localhost (as in `docker-compose.yml`) and never commit `.env`.
