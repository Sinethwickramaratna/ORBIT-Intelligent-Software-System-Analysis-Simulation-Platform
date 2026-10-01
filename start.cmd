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
if errorlevel 1 exit /b 1

rem Small helper (minimized window) that applies folder changes made in ORBIT's Settings by re-creating the containers.
start "ORBIT helper" /min cmd /c "scripts\orbit-watch.cmd"

echo.
echo ORBIT is starting - open http://localhost:3000
