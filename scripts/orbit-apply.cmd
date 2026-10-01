@echo off
rem Part of the ORBIT helper: re-creates the containers so new folders are mounted. Output goes to .orbit-signal\apply.log
cd /d "%~dp0.."
title ORBIT apply - re-creating containers (this can take a minute, please do not close or press Ctrl+C)
>>".orbit-signal\apply.log" echo === %date% %time% docker compose up -d
docker compose up -d >> ".orbit-signal\apply.log" 2>&1
call :logexit
del /q ".orbit-signal\running" >nul 2>&1
exit /b 0

:logexit
>>".orbit-signal\apply.log" echo exit code: %errorlevel%
exit /b 0
