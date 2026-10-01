@echo off
rem ORBIT host helper (started by start.cmd). When you change the folders in ORBIT's Settings the app cannot
rem re-create its own Docker containers, so it leaves a small request file in .orbit-signal. This script, running on
rem your computer, sees it and runs "docker compose up -d" (no rebuild; your data is kept).
rem It also touches .orbit-signal\watcher every few seconds so the app knows it can apply changes by itself.
rem Close its window (or sign out / shut down) to stop it.
cd /d "%~dp0.."
if not exist ".orbit-signal" mkdir ".orbit-signal"
title ORBIT helper - applies folder changes (you can minimize this window)

rem Only one helper at a time: the second one cannot open the locked file and stops.
( call :loop ) 9>>".orbit-signal\watcher.lock" 2>nul
exit /b 0

:loop
echo %date% %time%> ".orbit-signal\watcher"
if exist ".orbit-signal\apply" (
  del /q ".orbit-signal\apply" >nul 2>&1
  echo === %date% %time% docker compose up -d>> ".orbit-signal\apply.log"
  docker compose up -d >> ".orbit-signal\apply.log" 2>&1
  echo exit code: %errorlevel%>> ".orbit-signal\apply.log"
)
ping -n 4 127.0.0.1 >nul
goto loop
