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
