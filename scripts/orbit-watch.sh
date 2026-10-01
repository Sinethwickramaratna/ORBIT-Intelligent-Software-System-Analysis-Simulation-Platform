#!/bin/sh
# ORBIT host helper (started by start.sh). When you change the folders in ORBIT's Settings the app cannot re-create
# its own Docker containers, so it leaves a small request file in ./.orbit-signal. This script, running on your
# computer, sees it and runs `docker compose up -d` (no rebuild; your data is kept).
# It also touches ./.orbit-signal/watcher every few seconds so the app knows it can apply changes by itself.
# Stop it with: ./scripts/orbit-watch.sh stop      (it also ends when you shut the computer down)
cd "$(dirname "$0")/.." || exit 1
SIG=.orbit-signal
PIDFILE="$SIG/watcher.pid"
mkdir -p "$SIG"

if [ "$1" = "stop" ]; then
  [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null
  rm -f "$PIDFILE" "$SIG/watcher"
  echo "ORBIT helper stopped."
  exit 0
fi

# only one helper at a time
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  exit 0
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE" "$SIG/watcher"; exit 0' INT TERM EXIT

while :; do
  date > "$SIG/watcher"
  if [ -f "$SIG/apply" ]; then
    rm -f "$SIG/apply"
    {
      echo "=== $(date) docker compose up -d"
      docker compose up -d
      echo "exit code: $?"
    } >> "$SIG/apply.log" 2>&1
  fi
  sleep 3
done
