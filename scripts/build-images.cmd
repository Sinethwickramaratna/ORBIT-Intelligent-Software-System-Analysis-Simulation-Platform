@echo off
rem Build the three ORBIT images with a chosen layer-compression algorithm (BuildKit --output).
rem   scripts\build-images.cmd [zstd^|gzip^|estargz^|uncompressed] [--push registry/prefix]
rem Compression changes the size of the image when STORED/TRANSFERRED (tar, registry), not the size `docker images` shows.
setlocal
cd /d "%~dp0.."
set "ALGO=zstd"
set "PUSH="
:args
if "%~1"=="" goto run
if /i "%~1"=="zstd" set "ALGO=zstd"
if /i "%~1"=="gzip" set "ALGO=gzip"
if /i "%~1"=="estargz" set "ALGO=estargz"
if /i "%~1"=="uncompressed" set "ALGO=uncompressed"
if /i "%~1"=="--push" (set "PUSH=%~2" & shift)
shift
goto args
:run
docker buildx inspect orbit-builder >nul 2>&1 || docker buildx create --name orbit-builder --driver docker-container >nul
if "%NEXT_PUBLIC_API_URL%"=="" set "NEXT_PUBLIC_API_URL=http://localhost:8080"
if not exist dist mkdir dist
set "OPTS=compression=%ALGO%,force-compression=true,oci-mediatypes=true"
if /i "%ALGO%"=="uncompressed" set "OPTS=compression=uncompressed,oci-mediatypes=true"

call :build backend  backend  ""
if errorlevel 1 exit /b 1
call :build frontend frontend "--build-arg NEXT_PUBLIC_API_URL=%NEXT_PUBLIC_API_URL%"
if errorlevel 1 exit /b 1
call :build postgres infrastructure\postgresql ""
if errorlevel 1 exit /b 1

if "%PUSH%"=="" (
  echo.
  echo Compressed archives ^(%ALGO%^):
  for %%F in (dist\orbit-*.tar) do echo   %%F  %%~zF bytes
)
exit /b 0

:build
set "NAME=%~1"
set "CTX=%~2"
set "EXTRA=%~3"
if "%PUSH%"=="" (set "OUT=type=oci,dest=dist/orbit-%NAME%.tar,%OPTS%") else (set "OUT=type=image,name=%PUSH%/orbit-%NAME%:latest,push=true,%OPTS%")
echo =^> orbit-%NAME%  ^(%ALGO%^)
docker buildx build --builder orbit-builder --output "%OUT%" %EXTRA% "%CTX%"
exit /b %errorlevel%
