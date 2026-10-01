#!/bin/sh
# ORBIT launcher: prepares .env, checks Docker, then builds and starts everything.
cd "$(dirname "$0")" || exit 1

# A missing .env makes Docker create a FOLDER with that name - remove such an (empty) folder.
if [ -d .env ]; then
  if rmdir .env 2>/dev/null; then
    echo 'Removed the empty ".env" folder created by an earlier Docker run.'
  else
    echo '[ERROR] ".env" is a non-empty folder. Delete or rename it, then run this again.' >&2
    exit 1
  fi
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example - enter your secret key and DB credentials in the web setup screen."
fi

if ! docker info >/dev/null 2>&1; then
  echo '[ERROR] Docker is not running. Start Docker and run this again.' >&2
  exit 1
fi

docker compose up -d --build || exit 1

# Small helper that applies folder changes made in ORBIT's Settings (re-creates the containers for you).
# Stop it with: ./scripts/orbit-watch.sh stop
mkdir -p .orbit-signal
nohup sh scripts/orbit-watch.sh >/dev/null 2>&1 &

# Offer (once) to start that helper automatically at sign-in, so it also runs after a reboot.
if ! sh scripts/orbit-watch.sh installed && [ ! -f .orbit-signal/no-autostart ] && [ -t 0 ]; then
  printf "Start the ORBIT helper automatically when you sign in? [Y/n] "
  read -r answer
  case "$answer" in
    n|N|no|NO) touch .orbit-signal/no-autostart ;;
    *) sh scripts/orbit-watch.sh install ;;
  esac
fi

echo
echo "ORBIT is starting - open http://localhost:3000"
