# ORBIT — Intelligent Software System Analysis & Simulation Platform

ORBIT registers software projects (a folder on your disk or a cloned public Git repository) and analyses them: which
**languages** they use (files and source lines), which **build systems**, **configuration files** and **frameworks** they
have - every finding with the evidence that proves it. Next.js frontend · Spring Boot 4 backend · PostgreSQL in Docker.

Implemented so far: first-run setup, login (JWT + refresh tokens), projects (create / clone / open / delete), a file
explorer with a code editor (open, edit, `Ctrl+S` save; new file / folder, rename, delete), the project scan with its four result windows and the dependency check + one-click dependency setup
(Architecture Reconstruction, step 1). `llm-service/` and `ml-service/` are reserved for later phases.

```
Orbit/
├── docker-compose.yml          # postgres + backend + frontend
├── .env.example                # SAMPLE with empty secrets  →  copy to .env
├── .env                        # your own settings, written by the setup screen (git-ignored)
├── start.cmd / start.sh        # start everything (+ the folder helper, see below)
├── scripts/                    # orbit-watch.* (folder helper), orbit-apply.cmd, build-images.* (compressed images)
├── infrastructure/postgresql/  # Postgres image: starts once DB credentials exist, SQL backup + restore
├── backend/                    # Spring Boot 4 (Java 17+), Maven wrapper, Dockerfile
│   └── src/main/java/com/orbit/backend/
│       ├── config/             # security, dynamic data source (DB connects after setup), properties
│       ├── controller/         # REST API: auth, setup, users, projects, settings
│       ├── service/            # business logic: auth/JWT, setup/.env, projects, git init/clone, scan
│       ├── scan/               # scanners: languages, line counter, build systems, config files, frameworks
│       ├── deps/               # dependency check + Setup Dependencies (npm, Python, Maven, Gradle, NuGet, pub)
│       ├── entity/  repository/  dto/  filter/  exception/
│       └── ../resources/db/migration/   # Flyway V1–V8 (schema + detection catalogs + dependency check)
├── frontend/                   # Next.js (App Router, TypeScript, static export), nginx, Dockerfile
│   └── src/
│       ├── app/                # page, layout, global + home styles
│       ├── components/         # onboarding screens, home screen, dialogs, file tree, analysis windows
│       └── lib/                # API client (auto token refresh), themes, folder-apply hook
├── .github/workflows/main.yml  # CI: preflight checks, backend tests, frontend type-check + build
├── projects/                   # default projects folder when no drives are listed (git-ignored)
├── backups/                    # automatic SQL backups of the database (git-ignored)
└── llm-service/  ml-service/   # reserved for later phases
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
4. **Or clone a repository:** click **Clone Git Repository**, paste a public `http(s)` URL, press **Check Repository**,
   pick one of its branches, choose where to clone it and click **Clone Repository**. When the clone is done it becomes a project.
   (Private repositories - SSH key / access token - are not supported yet.)
5. **Open a project:** it opens right away and is scanned automatically. The Explorer shows its folders and files
   (loaded one level at a time) and, below them, the **Analysis**, **Build System**, **Configuration** and **Framework**
   windows (see *Project analysis*). Click a project in the left **Projects** panel any time to open it again
   (the arrow on the panel edge or `Ctrl+B` hides the panel; `Ctrl+J` hides the Analysis window).
6. **Dependencies (Architecture Reconstruction, step 1):** when a project is opened the top bar shows **Dependency Check**
   and a status icon: a **green check** when every dependency is installed, a **red X** when some are missing. The check
   runs by itself when the project is added; press **Dependency Check** to run it again. When something is missing, press
   **Setup Dependencies**, confirm, and ORBIT downloads everything into the project folder - the red X turns into the green
   check when it is done (see *Dependency check and setup*).
7. **Edit files (like an IDE):** click a file in the Explorer and it opens in a tab in the middle (line numbers, Tab /
   Shift+Tab indent, Enter keeps the indentation, browser Undo/Redo). A dot on the tab means unsaved changes; press
   **Ctrl+S** (Cmd+S on macOS) to save it to disk. Closing a modified tab asks *Save / Don't Save / Cancel*; if another
   program changed the file meanwhile, ORBIT asks before overwriting it. Only UTF-8 text files up to 5 MB open in the
   editor (binary files are refused, so they can never be damaged); line endings (LF / CRLF) and a BOM are kept.
   The project tab (first tab) shows the Architecture view again.
8. **New File / New Folder, Rename, Delete:** the two icons at the right end of the project-name row in the Explorer
   create a **New File** or **New Folder** - inside the selected folder, next to the selected file, or in the project
   root when nothing is selected (click empty space to clear the selection). Like Visual Studio, the item is created
   at once as `NewFile1.txt` / `NewFolder1` with its name ready to edit: type a name and press Enter (Escape keeps the
   default). **Right-click** gives the same in a menu: on a folder, New File / New Folder go *inside* that folder; on a
   file, next to it; on empty space below the files (or on the project name), into the project root. Right-clicking a
   file or folder also offers **Rename** (`F2`) and **Delete** (`Delete` key; it asks first and removes it from disk,
   folders with everything in them). Open tabs follow renamed files and folders.
9. **Scan again:** after the folder changed, press **Scan** in the Explorer; a progress bar shows how far it is.
10. **Delete a project:** the three dots next to a project -> **Delete**, then type its name. This removes it from ORBIT
   only - the files on disk are kept.
11. **Add or change drives later:** gear icon in the Projects panel -> **Settings** -> edit the folders -> **Save**, then
   ORBIT applies it by itself (the small ORBIT helper window that `start.cmd` opens does this).
12. **Stop / reset:** `docker compose down` stops everything and keeps your data; see *Database persistence* to start from zero.

## Project analysis

A scan runs when a project is created or cloned and whenever you press **Scan**. Every scan is stored (`project_scan`)
with its results; the windows show the newest one. Dependency and build-output folders (`node_modules`, `.git`,
`target`, `build`, `dist`, `venv`, `__pycache__`, `.venv`, `.next`, `.gradle` - `orbit.scan.ignored-folders`) are
skipped, symbolic links are never followed and files above 2 MB (`orbit.scan.max-file-bytes`) are left out.

| Window | What it shows | How it is found |
|---|---|---|
| **Analysis** | share of each language as a bar chart, with files and source lines | file extension (`language_extensions`); a small lexer counts lines with code - blank and comment-only lines are not counted, comment markers inside strings are not mistaken for comments |
| **Build System** | each build system with its evidence files | evidence file names / patterns (`build_file_type`): `pom.xml`, `build.gradle`, `package.json`, `CMakeLists.txt`, ... |
| **Configuration** | each configuration file and where it is | file name (`configuration_file`) |
| **Framework** | each framework with the dependency declarations that prove it, e.g. `pom.xml 9:19 spring-boot-starter-web` | dependency metadata, not a text search: `pom.xml`, `build.gradle(.kts)`, `package.json`, `requirements*.txt`, `pyproject.toml`, `Pipfile`, `pubspec.yaml`, `*.csproj` are parsed and the declared names compared with `framework_dependency` (only for the same package manager; `name-*` is a prefix rule) |

Detected out of the box:

* **Languages:** Java, Python, JavaScript, TypeScript, C/C++, C#, Dart, SQL
* **Build systems:** Maven, Gradle, Ant, npm, Yarn, pnpm, pip (requirements), Pipenv, Python packaging, Poetry, CMake, Make, Meson, NuGet, Dart pub
* **Configuration files:** `application.yml` / `.yaml` / `.properties`, `Dockerfile`, `docker-compose.yml` / `.yaml`, `package.json`, `requirements.txt`
* **Frameworks:** Spring Boot, Spring Framework, Spring Data, Quarkus, Micronaut, Vert.x, Dropwizard, Apache Struts, FastAPI,
  Flask, Django, Tornado, Pyramid, Streamlit, TensorFlow, PyTorch, Express, React, Next.js, Vue.js, Angular, NestJS, Svelte,
  Nuxt, Fastify, Koa, Electron, React Native, Flutter, ASP.NET Core

The scanners contain no concrete names: everything above is seed data in the Flyway migrations (V3–V7), so a new
language, build system, configuration file or framework is added with a new migration.

## Dependency check and setup

Architecture Reconstruction needs every library a project uses on disk, so its first step is the dependency check.

* **Dependency Check** (top bar, next to the open project) reads the project's manifests and looks at what is installed.
  It runs automatically when a project is created or cloned (and when a project that was never checked is opened).
  The icon next to the button is a **green check** when everything is installed (or nothing is declared) and a
  **red X** when something is missing. The middle area (*Architecture Reconstruction*) lists every manifest with each
  dependency, the version it requires, the version installed and its state.
* **Setup Dependencies** appears when something is missing. A confirmation window says what will be downloaded, from
  where and into which folder; after **Yes, Set Up** a **progress window** opens: an overall progress bar, one row per
  manifest (waiting / working / done / failed, with what happened), the package being downloaded, how many packages
  are installed or failed, the elapsed time and a live activity log. **Run in Background** hides it (the top bar keeps a
  small progress bar - click it to open the window again) and **Cancel Setup** stops at once: the downloads in flight
  are aborted (half-downloaded files are thrown away), queued packages are not started, a running `python -m venv` is
  stopped. What was completely installed stays.
  At the end the check runs again - the red X becomes the green check - and the window stays as the record of what
  happened (**View Details** in the panel opens it again). Anything that could not be installed is listed.
* **Only what the code uses is downloaded.** Declared dependencies the source code never uses are shown as *Not used*,
  do not make the check red and are not downloaded - nor what only they pull in. ORBIT reads the imports of the
  JavaScript / TypeScript / CSS, Python (also notebooks) and Dart files, plus the names mentioned in configuration, build
  and CI files (package.json scripts and their commands, ESLint / Babel / PostCSS / Jest configs, `angular.json`,
  Dockerfiles, Procfiles, workflows, `[tool.x]` tables, `analysis_options.yaml` ...). It is generous: a package counts as
  used if any of its import names (`PyYAML` -> `yaml`, `beautifulsoup4` -> `bs4`, `djangorestframework` ->
  `rest_framework` in `INSTALLED_APPS`), commands, plugin short names (`eslint-plugin-react` -> `react`) or typical
  implicit uses (`psycopg2` when the code talks to PostgreSQL, `@types/x` with `x`, `pytest-*` with tests) appears. A
  folder without any source code of the language keeps every dependency. Maven / Gradle and .NET dependencies are
  always all installed (their packages do not map reliably to the code's packages). The confirmation window can
  download the unused ones too (*Also download ... the code does not use*).
* **Python packages always go into a virtual environment of the project**, never into the computer's Python: an
  existing one (`.venv`, `venv`, `env` or any folder with a `pyvenv.cfg`, next to the requirements or above), otherwise
  setup creates `.venv`. When ORBIT runs directly on your computer and finds Python (`py` / `python3` / `python`), this is
  a real `python -m venv` and the packages are chosen for that Python version. In Docker there is no Python of your
  computer to call, so ORBIT writes the venv layout (`pyvenv.cfg`, `site-packages`) and you run
  `python -m venv .venv` once in that folder to add the interpreter - the installed packages are kept.
* While **Dependency Check** runs, a progress bar in the top bar and in the panel shows which manifest is being checked.
* **Pinned Python versions that cannot be installed** (for example `numpy==1.21.0`, which has no ready-made package for
  Python 3.12 on Windows): with the option in the confirmation window (on by default), ORBIT installs the newest version
  that works on your computer - the newest one with the **same major version** first (`cffi==1.15.0` -> `1.17.x`), only if
  there is none the newest overall (`pyyaml==5.3.1` -> `6.0.x`) - and rewrites that line of `requirements*.txt`, keeping
  extras, markers and comments: `pyyaml==6.0.3  # updated by ORBIT: was ==5.3.1 - no ready-made package (wheel) for Windows / Python 3.12`.
  Before its first change ORBIT copies the file to `requirements.txt.orbit-backup`. The progress window lists every
  changed version. Lines it cannot rewrite safely (continued with `\`, hash-checked with `--hash`) and versions declared
  in `pyproject.toml` / `Pipfile` are not changed: the setup reports which version would work instead. With the option
  switched off, your files are never changed.
* Nothing needs to be installed on your computer (or in the Docker image) for this: ORBIT downloads the packages itself
  over HTTPS and verifies the checksums the registries publish. Packages are chosen for **your** operating system even
  when ORBIT runs in Docker (read from the project location: `E:\...` = Windows, `/Users/...` = macOS, otherwise
  Linux; override with `ORBIT_HOST_OS` = windows|mac|linux and `ORBIT_HOST_ARCH` = x64|arm64). Install scripts of
  packages (npm `postinstall`, ...) are never run, and your own files are not changed.

| Ecosystem | Manifests read | Setup downloads into (inside the project folder) | Also counts as installed |
|---|---|---|---|
| Node.js | `package.json` (+ `package-lock.json` / `npm-shrinkwrap.json`, workspaces) | `node_modules` - exactly the lockfile versions, otherwise npm-style resolution and hoisting | - |
| Python | `requirements*.txt` (with `-r`), `pyproject.toml` (PEP 621 and Poetry), `Pipfile` | the project's `.venv` / `venv` / `env`, or a new `.venv` next to the manifest (wheels for your OS and Python version) | - |
| Maven | `pom.xml` (modules, parents, BOMs, `dependencyManagement`, properties) | `.orbit/maven-repository` (a standard Maven repository) | `~/.m2/repository` |
| Gradle | `build.gradle(.kts)`, `gradle/libs.versions.toml`, `platform(...)` and Spring Boot BOMs | `.orbit/maven-repository` | `~/.m2`, `~/.gradle/caches` |
| .NET | `*.csproj` / `*.fsproj` / `*.vbproj`, `Directory.Packages.props`, `packages.config` | `.orbit/nuget-packages` (layout of NuGet's global packages folder) | `~/.nuget/packages` |
| Dart / Flutter | `pubspec.yaml` (+ `pubspec.lock`) | `.orbit/pub-cache` (layout of the pub cache) | `.dart_tool/package_config.json`, your pub cache |
| C / C++ | `CMakeLists.txt`, `Makefile`, `meson.build` | nothing: system libraries have no package registry (shown as information) | - |

States of a dependency: *Installed*, *Missing*, *Wrong version* (installed, but not the version the manifest accepts),
*In project* (a module / workspace package / local path), *From SDK* (e.g. `flutter: sdk: flutter`), *Manual*
(git or URL dependencies, which have to be installed with the ecosystem's own tool) and *Not used* (declared, but the
code never uses it: not downloaded). The `.orbit` folder gets its own
`.gitignore`, so the downloads are never committed; the scan skips `.orbit` and `.dart_tool` like `node_modules`.

Settings (`orbit.dependencies.*` in `application.yaml`): `host-os`, `host-arch`, `python-version` (default 3.12, used
when a project has no virtual environment yet), `parallel-downloads`, `max-download-mb`, `user-caches` and the registry
URLs (`npm-registry`, `pypi-url`, `maven-repositories`, `nuget-url`, `pub-url`).

The same check and setup are available on the command line, also inside the Docker image:

```bash
java -cp app.jar -Dloader.main=com.orbit.backend.deps.DependencyCli \
     org.springframework.boot.loader.launch.PropertiesLauncher check|setup <project folder> [windows|mac|linux] [--no-update] [--all]
```

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
5. **Sign in** – then the home page with the Projects panel, **Create New Project**, **Clone Git Repository** and **Sign Out**.

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

`start.cmd` / `./start.sh` also ask once whether the helper should **start automatically when you sign in** (default
yes), so it keeps working after a reboot. It uses the Startup folder on Windows, a LaunchAgent on macOS and a systemd
user service (or desktop autostart entry) on Linux. Change your mind any time: `scripts\orbit-watch.cmd uninstall` /
`install` (Windows) or `./scripts/orbit-watch.sh uninstall` / `install`; `stop` stops it.

If ORBIT cannot apply a change by itself it tells you why after **Save**: either the helper is not running (run
`start.cmd` / `./start.sh` - that applies the change), or your containers were created before this feature existed
(run `start.cmd` / `./start.sh` once to update them - this is only needed one time).

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
| `projects` | `project_id` UUID PK, `project_name`, `location`, `project_type` (enum: WEB_APPLICATION … OTHER), `description` (optional), `user_id` FK→users (1 user : N projects), `created_at`, `updated_at`; unique (`user_id`, `location`) |
| `project_scan` | `scan_id` UUID PK, `project_id` FK→projects, `scanned_at` (one row per scan) |
| `language` / `language_extensions` | catalog: `language_name`; `extension_type` (`.java`) → `language_id` |
| `project_language_detail` | PK (`scan_id`, `language_id`), `lines_of_code`, `number_of_files` |
| `build_system` / `build_file_type` | catalog: `name`; `file_type` (file name or `*.ext`) → `build_system_id` |
| `project_build_detail` / `project_build_evidence` | PK (`scan_id`, `build_system_id`); evidence adds `file_path` |
| `configuration_file` | catalog: `file_name` |
| `project_configuration_detail` | PK (`scan_id`, `configuration_file_id`, `file_path`) |
| `framework` / `framework_dependency` | catalog: `name`; `dependency_name` + `package_manager` (maven, npm, pip, pub, nuget) → `framework_id` |
| `project_framework_detail` / `project_framework_evidence` | PK (`scan_id`, `framework_id`); evidence: `evidence_id` PK, `dependency_name`, `file_path`, `line_number`, `col_number` |
| `project_dependency_check` | `check_id` UUID PK, `project_id` FK→projects, `checked_at`, `status` (INSTALLED / MISSING / NO_DEPENDENCIES), `total_count`, `missing_count`, `target_platform` - only the newest check per project is kept |
| `project_dependency_unit` | `unit_id` UUID PK, `check_id` FK, `position`, `ecosystem`, `manifest_path`, `install_location`, `status`, `message` |
| `project_dependency_item` | `item_id` UUID PK, `unit_id` FK, `position`, `dependency_name`, `required_version`, `installed_version`, `scope`, `state`, `note` |

Deleting a project deletes its scans and all scan results (`ON DELETE CASCADE`). The full diagram is in `orbit-er-diagram.mermaid`.

## API

Public: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`,
`GET /api/setup/status`, `POST /api/setup/environment`, `POST /api/setup/database/retry`, `GET|PUT /api/settings/theme`.
Protected (`Authorization: Bearer <access token>`): `POST /api/auth/logout`, `GET /api/users`, `GET /api/users/me`,
`GET|PUT|DELETE /api/users/{id}` (update/delete: own account only).
Projects (own projects only): `GET|POST /api/projects`, `GET /api/projects/config`, `GET /api/projects/inspect?location=`,
`GET /api/projects/{id}`, `GET /api/projects/{id}/tree?path=` (one folder level, lazily),
Files (paths relative to the project root; nothing outside it, nothing in `.git`): `GET /api/projects/{id}/file?path=`
(open a UTF-8 text file: `{path, name, content, size, lastModified, bom, lineEnding}`; `415 FILE_NOT_TEXT` for binary /
non-UTF-8, `413 FILE_TOO_LARGE` above 5 MB), `PUT /api/projects/{id}/file` `{path, content, bom, expectedLastModified?}`
(save, `Ctrl+S`; `409 FILE_CHANGED_ON_DISK` when the file changed since `expectedLastModified`),
`POST /api/projects/{id}/files` `{parent, name, kind: FILE|FOLDER}` (New File / New Folder; `409 FILE_ALREADY_EXISTS`,
`400 FILE_NAME_INVALID`), `POST /api/projects/{id}/files/rename` `{path, newName}` (Rename, stays in its folder) and
`DELETE /api/projects/{id}/files?path=` (Delete a file, or a folder with everything in it; the project root cannot be deleted),
`GET /api/projects/browse?path=` and `POST /api/projects/browse/folder` (folder chooser: only the mounted folders in Docker),
`DELETE /api/projects/{id}?confirmName=` (removes it from ORBIT; files on disk are kept),
`GET|PUT /api/settings/folders` (the drives/folders ORBIT may open).
Clone: `POST /api/projects/clone/inspect` `{repositoryUrl}` (owner, default branch, branches of a public repository) and
`POST /api/projects/clone` `{repositoryUrl, branch, location, projectName, description?}` (returns when the clone is complete).
Scan: `POST /api/projects/{id}/scan` (runs a new scan and returns it), `GET /api/projects/{id}/scan` (newest scan;
`204` when never scanned), `GET /api/projects/{id}/scan/progress` (`{active, phase, percent}` for the progress bar).
Dependencies: `GET /api/projects/{id}/dependencies` (stored check; `204` when never checked),
`POST /api/projects/{id}/dependencies/check` (the Dependency Check button), `GET /api/projects/{id}/dependencies/check/progress`
(progress of the running / last check), `POST /api/projects/{id}/dependencies/setup`
(Setup Dependencies, `202`; optional body `{updateManifests: true|false, skipUnused: true|false}`, both default true; `409 DEPENDENCY_SETUP_RUNNING` when one is running),
`GET /api/projects/{id}/dependencies/setup/progress` (`{kind, state: IDLE|RUNNING|SUCCEEDED|FAILED|CANCELLED, phase, current,
percent, steps[{title, state, summary}], installed, failed, failures, log}`) and `DELETE /api/projects/{id}/dependencies/setup` (cancel).

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

`cd backend && ./mvnw test` — on in-memory H2 (no Docker needed):

* unit tests for every scanner (`LanguageScanner`, `LineCounter`, `BuildSystemScanner`, `ConfigFileScanner`,
  `DependencyReader`, `FrameworkScanner`) and the services (setup / `.env`, folder browse and settings, path
  resolution, project files - listing, open / save, New File / New Folder, Rename, Delete and the path / name checks -,
  git init and clone);
* the dependency check of every ecosystem (`DependencyCheckerTest`) and the version-range rules of npm/pub, PEP 440 and
  NuGet (`SemverTest`, `Pep440Test`, `NuGetVersionTest`);
* integration tests for the whole authentication flow, the project API and the dependency API
  (`DependencyApiIntegrationTest`: automatic check on create, check button, setup job).

Real downloads from npm, PyPI, Maven Central, nuget.org and pub.dev need internet and run only on request:
`./mvnw test -Dtest=DependencySetupNetworkTest -Dorbit.networkTests=true`.

Frontend: `cd frontend && npm run typecheck && npm run build`.

CI (`.github/workflows/main.yml`) runs on every push to `main`: preflight checks (required files and tools, valid
compose file, Maven dependencies, npm lockfile in sync), the backend tests, and the frontend type-check and build.

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
