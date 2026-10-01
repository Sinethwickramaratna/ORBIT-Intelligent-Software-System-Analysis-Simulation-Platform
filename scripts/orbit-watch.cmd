@echo off
rem ORBIT host helper (started by start.cmd). When you change the folders in ORBIT's Settings the app cannot
rem re-create its own Docker containers, so it leaves a small request file in .orbit-signal. This script, running on
rem your computer, sees it and runs "docker compose up -d" (no rebuild; your data is kept).
rem It also touches .orbit-signal\watcher every few seconds so the app knows it can apply changes by itself.
rem
rem   scripts\orbit-watch.cmd             run the helper (start.cmd does this for you)
rem   scripts\orbit-watch.cmd stop        stop it
rem   scripts\orbit-watch.cmd install     start it (hidden) every time you sign in to Windows
rem   scripts\orbit-watch.cmd uninstall   remove that again
cd /d "%~dp0.."
if not exist ".orbit-signal" mkdir ".orbit-signal"
set "VBS=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\ORBIT helper.vbs"

if /i "%~1"=="install" goto :install
if /i "%~1"=="uninstall" goto :uninstall
if /i "%~1"=="stop" goto :stop
if /i "%~1"=="installed" ( if exist "%VBS%" (exit /b 0) else (exit /b 1) )

title ORBIT helper - applies folder changes (you can minimize this window)
del /q ".orbit-signal\running" >nul 2>&1
rem Only one helper at a time, and the NEWEST one wins: every helper writes a random id to watcher.id and ends as
rem soon as it finds a different id there. So a helper that got stuck (for example at a "Terminate batch job" prompt
rem after Ctrl+C) can never block a new one.
set "ME=%RANDOM%%RANDOM%%RANDOM%"
>".orbit-signal\watcher.id" echo %ME%
call :loop
exit /b 0

:install
>"%VBS%" echo CreateObject("WScript.Shell").Run "cmd /c ""%~f0""", 0, False
if errorlevel 1 (
  echo Could not write "%VBS%".
  exit /b 1
)
echo The ORBIT helper will start automatically when you sign in to Windows.
echo Remove it with: scripts\orbit-watch.cmd uninstall
rem start it right now too (hidden), unless it already runs
wscript "%VBS%"
exit /b 0

:uninstall
del /q "%VBS%" >nul 2>&1
call :stop
echo Automatic start of the ORBIT helper removed.
exit /b 0

:stop
>".orbit-signal\watcher.id" echo stop
taskkill /f /fi "WINDOWTITLE eq ORBIT helper*" /im cmd.exe >nul 2>&1
del /q ".orbit-signal\watcher" >nul 2>&1
echo ORBIT helper stopped.
exit /b 0

:loop
set "CUR="
set /p CUR=<".orbit-signal\watcher.id"
if not "%CUR%"=="%ME%" exit /b 0
>".orbit-signal\watcher" echo %date% %time%
if exist ".orbit-signal\apply" if not exist ".orbit-signal\running" (
  del /q ".orbit-signal\apply" >nul 2>&1
  >".orbit-signal\running" echo x
  rem docker compose up -d can take minutes because it waits for the backend, so it runs in its own minimized window and
  rem this helper keeps writing its heartbeat meanwhile. Do not press Ctrl+C in that window.
  start "ORBIT apply" /min cmd /c "scripts\orbit-apply.cmd"
)
ping -n 4 127.0.0.1 >nul
goto loop
