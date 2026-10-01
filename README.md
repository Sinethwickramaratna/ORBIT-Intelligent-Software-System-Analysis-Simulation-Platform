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

## Quick guide - using ORBIT

1. **Start it:** double-click `start.cmd` (Windows) or run `./start.sh`, then open http://localhost:3000.
2. **First-run screens (once):** pick a theme -> enter a secret key (use *Generate*), a database username/password and the
   drives/folders ORBIT may open -> wait for the database -> create your account -> sign in.
3. **Create a project:** on the home page click **Create New Project**, enter a name, press the folder button to choose the
   project folder (or type it), pick the project type, add an optional description, keep *Initialize Git repository*
   ticked for a new folder (it is skipped automatically if the folder already is a Git repository) and click **Create Project**.
4. **Open a project:** it opens right away, showing its folders and files on the right; click a project in the left
   **Projects** panel any time to open it again (the arrow on the panel edge or `Ctrl+B` hides the panel).
5. **Add or change drives later:** gear icon in the Projects panel -> **Settings** -> edit the folders -> **Save**, then
   ORBIT applies it by itself (the small ORBIT helper window that `start.cmd` opens does this).
6. **Stop / reset:** `docker compose down` stops everything and keeps your data; see *Database persistence* to start from zero.

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
   | `ORBIT_MOUNT_1` … `ORBIT_MOUNT_8` | yes (optional) | drives/folders ORBIT may open, see below |

   Allowed characters in the secret/password: letters, digits and `!@%^&*()_+-=[]{};:,.<>?/~|` (no spaces, quotes, `$`, `#`, `\`), because the same file is read by Spring, Docker Compose and a shell script.
3. **Database** – PostgreSQL starts with your credentials; the backend connects and Flyway creates the tables.
   A progress screen shows this. If it fails (e.g. wrong credentials for an existing database) the reason is shown with a Retry button.
4. **Register** – no users exist yet, so you register a username + password (stored as an Argon2id hash).
5. **Sign in** – then the welcome screen with a **Log out** button.

The backend starts *without* a database (only `/api/setup/**` works; every other API answers `503 DATABASE_NOT_READY`) so the setup screen can run first.

## Which folders ORBIT can open (Docker)

The backend runs in a container and cannot see your disks by itself, so you choose the drives/folders it may open
(up to 8). The easiest way is the app itself:

* **First run:** the *Environment* screen has a **Folders ORBIT can open** section next to the secret key and database
  password. Type a drive or folder per row (`E:/`, `D:/Work`, `/Users/you/code`, `/home/you/code` - forward slashes),
  or leave it empty to use only ORBIT's own `projects` folder. If you upgraded from an older version, the screen
  appears once more with just this section.
* **Later:** click the gear (Settings) at the bottom of the Projects panel, edit the list and press **Save**.

Docker only applies a mount change when the containers are re-created, and the app cannot do that to itself (it
deliberately has no access to Docker). So `start.cmd` / `./start.sh` also start a tiny **helper** on your computer
(a minimized window on Windows, a background process on Linux/macOS; `scripts/orbit-watch.cmd` / `.sh`). When you
press **Save**, ORBIT leaves a request file in `.orbit-signal/`, the helper runs `docker compose up -d` and ORBIT
restarts for about a minute - nothing else to do, your database and projects are kept. Its log is
`.orbit-signal/apply.log`.

If the helper is not running (you started with plain `docker compose up`, closed its window, or rebooted), ORBIT
tells you so after **Save** - then run **`start.cmd`** (Windows) or **`./start.sh`** once (or `docker compose up -d`).
Stop the helper on Linux/macOS with `./scripts/orbit-watch.sh stop`.

The values are stored in `.env` as `ORBIT_MOUNT_1` ... `ORBIT_MOUNT_8` (plus `ORBIT_MOUNTS_CONFIGURED=true`); you can still
edit that file by hand:

```
ORBIT_MOUNT_1=E:/             # a whole drive (Windows)
ORBIT_MOUNT_2=D:/My Work      # or just a folder (spaces are fine)
# macOS: ORBIT_MOUNT_1=/Users/you/code      Linux: ORBIT_MOUNT_1=/home/you/code
```

In **Create New Project** the folder button opens a chooser that starts at the list of these drives/folders; you can also
type a path (`E:\Work\my-app` is translated to the matching mount). The path you see and the one stored in the database
are always your real path. Notes:

* ORBIT can read **and write** everything below a listed entry - list your project drives/folders, not a system drive.
* Not allowed in a path: ``" ' # $ ` | , * ? < >``, `..`, network paths (`\\server\share` - map it to a drive letter) and `/` alone.
* Nothing listed = the `./projects` folder next to `docker-compose.yml`. `ORBIT_PROJECTS_DIR` from older versions still works as slot 1.
* Docker Desktop (Windows/macOS) must be allowed to share the drive/folder (Settings → Resources → File sharing; automatic with WSL 2).
  On Linux the folder must be writable by the container user.
* Running the backend directly on your computer (Option B) needs no mounts: the chooser starts in your home folder and can reach every drive.

## Database persistence

Data lives in the Docker named volume `orbit_pgdata` on *your* machine (never in git).
`docker compose down`, restarts, rebuilds (`up --build`) and image upgrades keep it. As a safety net an SQL backup is written to the git-ignored `./backups` folder every few seconds; if the volume is ever removed (`down -v`, "delete volumes" in Docker Desktop, `docker volume prune`) the next start restores the users and settings from it. To start from zero on purpose, run `docker compose down -v` **and** delete the `backups` folder.

> PostgreSQL applies `DB_USERNAME`/`DB_PASSWORD` only when it initialises an **empty** data volume. Changing them in
> `.env` later does not change an existing database — run `docker compose down -v`, delete the `backups` folder and `copy .env.example .env` to start over with new credentials.

## Tables

| table | columns |
|---|---|
| `users` | `user_id` UUID PK, `user_name`, `password` (Argon2), `created_at`, `updated_at` |
| `refresh_token_table` | `token_id` UUID PK, `token`, `user_id` FK→users, `created_at`, `expired_at` |
| `app_settings` | `setting_key` PK, `setting_value`, `updated_at` (theme) |
| `projects` | `project_id` UUID PK, `project_name`, `location`, `project_type` (enum: WEB_APPLICATION … OTHER), `description` (optional), `user_id` FK→users (1 user : N projects), `created_at`, `updated_at` |

## API

Public: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`,
`GET /api/setup/status`, `POST /api/setup/environment`, `POST /api/setup/database/retry`, `GET|PUT /api/settings/theme`.
Protected (`Authorization: Bearer <access token>`): `POST /api/auth/logout`, `GET /api/users`, `GET /api/users/me`,
`GET|PUT|DELETE /api/users/{id}` (update/delete: own account only).
Projects (own projects only): `GET|POST /api/projects`, `GET /api/projects/config`, `GET /api/projects/inspect?location=`,
`GET /api/projects/{id}`, `GET /api/projects/{id}/tree?path=` (one folder level, lazily),
`GET /api/projects/browse?path=` and `POST /api/projects/browse/folder` (folder chooser: only the mounted folders in Docker),
`GET|PUT /api/settings/folders` (the drives/folders ORBIT may open).

`POST /api/setup/environment` accepts `{secretKey?, dbUsername?, dbPassword?, folders?}` and only the values that are still
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

## Smaller Docker images

What changed (structure — this is what shrinks the size `docker images` shows):

| Image | Before | Now |
|---|---|---|
| frontend | Node 22 runtime + standalone server | Next.js **static export** served by `nginx-unprivileged:alpine-slim` (no Node, no node_modules) |
| backend | `eclipse-temurin:17-jre-alpine` | multi-stage: Maven build → **jdeps + jlink** custom JRE (`backend/docker/make-jre.sh`) → bare `alpine` |
| postgres | `postgres:16-alpine` | same server, **JIT/LLVM removed** and the filesystem **flattened to one layer** (`infrastructure/postgresql/Dockerfile`) |

All three also use `.dockerignore`, cache mounts and no package-manager caches in the final layers.
Measure the result yourself: `docker images` (SIZE column) and `docker history orbit-backend`.

Layer compression (BuildKit `--output`):

```bash
scripts/build-images.sh zstd                    # or gzip | estargz | uncompressed -> ./dist/orbit-*.tar
scripts/build-images.sh zstd --push myrepo      # push myrepo/orbit-<name>:latest
scripts\build-images.cmd zstd                   # Windows
```

which runs `docker buildx build --output type=oci,dest=...,compression=zstd,force-compression=true,oci-mediatypes=true`.
**Important:** compression only shrinks what is *stored or transferred* (registry, `docker save`, pull time). The size that
`docker images` prints is the *uncompressed* size and is identical for gzip, zstd and estargz — `uncompressed` only matters for
local use. zstd needs a runtime that understands it (recent Docker/containerd); `estargz` additionally needs the stargz snapshotter
for lazy pulling; use `gzip` when in doubt.

Optional tools (run on your machine, not part of the build):

```bash
dive orbit-backend                          # browse layers, find wasted space
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock dslim/slim build --target orbit-frontend   # SlimToolkit
docker-squash -t orbit-backend:squashed orbit-backend   # flattens layers (the postgres Dockerfile already does this with `FROM scratch`)
```

SlimToolkit observes one run of the container and deletes everything it did not touch. It can break the backend (Spring loads classes
lazily) — use `--http-probe` against `/api/setup/status` and re-test the login flow before adopting a slimmed image.
