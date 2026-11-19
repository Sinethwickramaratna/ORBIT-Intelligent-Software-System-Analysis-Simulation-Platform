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

if ! docker compose up -d --build; then
  # Newer Docker versions can refuse to let Compose build these images ("additional privileges requested").
  # Plain "docker build" works everywhere, so build them one by one and start without rebuilding.
  echo "Docker Compose could not build the images - building them one by one instead..."
  env_value() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" .env | tail -n 1; }
  API_URL="$(env_value NEXT_PUBLIC_API_URL)"; API_URL="${API_URL:-http://localhost:8080}"
  TERM_URL="$(env_value NEXT_PUBLIC_TERMINAL_URL)"; TERM_URL="${TERM_URL:-http://localhost:8091}"
  docker build -t orbit-postgres:16-slim infrastructure/postgresql || exit 1
  docker build -t orbit-backend backend || exit 1
  docker build -t orbit-frontend --build-arg NEXT_PUBLIC_API_URL="$API_URL" --build-arg NEXT_PUBLIC_TERMINAL_URL="$TERM_URL" frontend || exit 1
  docker compose up -d --no-build || exit 1
fi

# Terminal helper: runs the terminals of ORBIT's bottom panel on this computer (built inside Docker for it).
sh scripts/orbit-terminal.sh start

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
