@echo off
rem ORBIT terminal helper: runs the terminals of ORBIT's bottom panel on this computer (cmd, PowerShell, Git Bash,
rem WSL, ...) in the project folder. It is built inside Docker for this computer - nothing else to install.
rem It listens on 127.0.0.1:8091 only and opens a terminal only with a one-time ticket from the ORBIT backend.
rem
rem   scripts\orbit-terminal.cmd start    build it (if changed) and start it in the background (start.cmd does this)
rem   scripts\orbit-terminal.cmd ensure   start the already built helper unless it runs (used at sign-in)
rem   scripts\orbit-terminal.cmd stop     stop it (and every terminal it runs)
rem   scripts\orbit-terminal.cmd status   show whether it answers
rem   scripts\orbit-terminal.cmd run      run it in this window, with its log on the screen
setlocal
cd /d "%~dp0.."
set "BIN=.orbit-bin\orbit-terminal.exe"
set "ARCH=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=arm64"
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" set "ARCH=arm64"

if /i "%~1"=="start" goto :start
if /i "%~1"=="ensure" goto :ensure
if /i "%~1"=="stop" goto :stop
if /i "%~1"=="status" goto :status
if /i "%~1"=="run" goto :run
echo Usage: scripts\orbit-terminal.cmd start ^| ensure ^| stop ^| status ^| run
exit /b 1

:start
echo Building the ORBIT terminal helper for windows/%ARCH% (inside Docker)...
if not exist ".orbit-bin" mkdir ".orbit-bin"
docker build -q --target export --build-arg AGENT_OS=windows --build-arg AGENT_ARCH=%ARCH% --output type=local,dest=.orbit-bin\build terminal-agent >nul
if errorlevel 1 (
  echo [WARNING] The terminal helper could not be built - the Terminal panel will not work. Is Docker running?
  if exist "%BIN%" goto :ensure
  exit /b 1
)
call :kill
copy /y ".orbit-bin\build\orbit-terminal.exe" "%BIN%" >nul
if errorlevel 1 (
  echo [WARNING] Could not replace %BIN% - is it still running?
  exit /b 1
)
goto :launch

:ensure
if not exist "%BIN%" (
  echo The terminal helper is not built yet - run scripts\orbit-terminal.cmd start
  exit /b 1
)
call :ping && (echo ORBIT terminal helper is running. & exit /b 0)
goto :launch

:launch
rem started hidden and detached from this window, so closing the window does not end the terminals
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath (Join-Path (Get-Location) '%BIN%') -WorkingDirectory (Get-Location) -ArgumentList '--root','.','--log','.orbit-bin\orbit-terminal.log' -WindowStyle Hidden"
for /l %%i in (1,1,20) do (
  call :ping && (echo ORBIT terminal helper started - http://127.0.0.1:8091 & exit /b 0)
  powershell -NoProfile -Command "Start-Sleep -Milliseconds 250"
)
echo [WARNING] The terminal helper did not start - see .orbit-bin\orbit-terminal.log
exit /b 1

:stop
call :kill
echo ORBIT terminal helper stopped.
exit /b 0

:status
call :ping && (echo ORBIT terminal helper is running on http://127.0.0.1:8091 & exit /b 0)
echo ORBIT terminal helper is not running.
exit /b 1

:run
if not exist "%BIN%" call :start >nul
call :kill
"%BIN%" --root .
exit /b %errorlevel%

:kill
rem /T also ends the shells it started
taskkill /f /t /im orbit-terminal.exe >nul 2>&1
powershell -NoProfile -Command "Start-Sleep -Milliseconds 300"
exit /b 0

:ping
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://127.0.0.1:8091/health; if ($r.Content -match 'ok') { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
exit /b %errorlevel%
