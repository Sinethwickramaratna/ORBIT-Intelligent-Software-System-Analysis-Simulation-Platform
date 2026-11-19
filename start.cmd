@echo off
rem ORBIT launcher: prepares .env, checks Docker, then builds and starts everything.
cd /d "%~dp0"

rem A missing .env makes Docker create a FOLDER with that name - remove such an (empty) folder.
if exist ".env\" (
  rmdir ".env" 2>nul
  if exist ".env\" (
    echo [ERROR] ".env" is a non-empty folder. Delete or rename it, then run this again.
    exit /b 1
  )
  echo Removed the empty ".env" folder created by an earlier Docker run.
)

if not exist ".env" (
  copy /y ".env.example" ".env" >nul
  echo Created .env from .env.example - enter your secret key and DB credentials in the web setup screen.
)

docker info >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker is not running. Start Docker Desktop, wait for "Engine running", then run this again.
  exit /b 1
)

docker compose up -d --build
if errorlevel 1 (
  rem Newer Docker versions can refuse to let Compose build these images ("additional privileges requested").
  rem Plain "docker build" works everywhere, so build them one by one and start without rebuilding.
  echo Docker Compose could not build the images - building them one by one instead...
  call :build_images || exit /b 1
  docker compose up -d --no-build || exit /b 1
)

rem Terminal helper: runs the terminals of ORBIT's bottom panel on this computer (built inside Docker for it).
call scripts\orbit-terminal.cmd start

rem Small helper (minimized window) that applies folder changes made in ORBIT's Settings by re-creating the containers.
if not exist ".orbit-signal" mkdir ".orbit-signal"
rem End any older helper first (it may be stuck), then start a fresh one.
call scripts\orbit-watch.cmd stop >nul 2>&1
start "ORBIT helper" /min cmd /c "scripts\orbit-watch.cmd"

rem Offer (once) to start that helper automatically when you sign in, so it also runs after a reboot.
call scripts\orbit-watch.cmd installed
if errorlevel 1 if not exist ".orbit-signal\no-autostart" (
  choice /c YN /t 15 /d Y /n /m "Start the ORBIT helper automatically when you sign in to Windows? [Y/n] "
  if errorlevel 2 (
    echo.>".orbit-signal\no-autostart"
  ) else (
    call scripts\orbit-watch.cmd install
  )
)

echo.
echo ORBIT is starting - open http://localhost:3000
exit /b 0

:build_images
rem same images and build arguments as docker-compose.yml (NEXT_PUBLIC_* may be set in .env)
set "API_URL=http://localhost:8080"
set "TERM_URL=http://localhost:8091"
for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do (
  if /i "%%a"=="NEXT_PUBLIC_API_URL" if not "%%b"=="" set "API_URL=%%b"
  if /i "%%a"=="NEXT_PUBLIC_TERMINAL_URL" if not "%%b"=="" set "TERM_URL=%%b"
)
docker build -t orbit-postgres:16-slim infrastructure\postgresql || exit /b 1
docker build -t orbit-backend backend || exit /b 1
docker build -t orbit-frontend --build-arg NEXT_PUBLIC_API_URL=%API_URL% --build-arg NEXT_PUBLIC_TERMINAL_URL=%TERM_URL% frontend || exit /b 1
exit /b 0
