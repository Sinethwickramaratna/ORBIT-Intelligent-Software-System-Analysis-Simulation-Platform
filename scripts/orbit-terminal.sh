#!/bin/sh
# ORBIT terminal helper: runs the terminals of ORBIT's bottom panel on this computer (bash, zsh, fish, ...) in the
# project folder. It is built inside Docker for this computer (macOS or Linux, Intel or ARM) - nothing else to install.
# It listens on 127.0.0.1:8091 only and opens a terminal only with a one-time ticket from the ORBIT backend.
#
#   ./scripts/orbit-terminal.sh start    build it (if changed) and start it in the background (start.sh does this)
#   ./scripts/orbit-terminal.sh ensure   start the already built helper unless it runs (used at sign-in)
#   ./scripts/orbit-terminal.sh stop     stop it (and every terminal it runs)
#   ./scripts/orbit-terminal.sh status   show whether it answers
#   ./scripts/orbit-terminal.sh run      run it in the foreground with its log on the screen
ROOT="$(cd "$(dirname "$0")/.." && pwd)" || exit 1
cd "$ROOT" || exit 1
BIN=.orbit-bin/orbit-terminal
PIDFILE=.orbit-bin/orbit-terminal.pid

case "$(uname -s)" in
  Darwin) OS=darwin ;;
  Linux) OS=linux ;;
  *) echo "Unsupported system $(uname -s) - on Windows use scripts\\orbit-terminal.cmd" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  arm64|aarch64) ARCH=arm64 ;;
  *) echo "Unsupported CPU $(uname -m)" >&2; exit 1 ;;
esac

ping() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -m 2 http://127.0.0.1:8091/health 2>/dev/null | grep -q '"ok"'
  else
    wget -qO- -T 2 http://127.0.0.1:8091/health 2>/dev/null | grep -q '"ok"'
  fi
}

kill_helper() {
  if [ -f "$PIDFILE" ]; then
    pid="$(cat "$PIDFILE")"
    # the helper ends the shells it started when it is stopped (SIGTERM)
    kill "$pid" 2>/dev/null && for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$pid" 2>/dev/null
    rm -f "$PIDFILE"
  fi
}

launch() {
  nohup "$ROOT/$BIN" --root "$ROOT" --log "$ROOT/.orbit-bin/orbit-terminal.log" >/dev/null 2>&1 &
  echo $! > "$PIDFILE"
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if ping; then echo "ORBIT terminal helper started - http://127.0.0.1:8091"; return 0; fi
    sleep 0.25
  done
  echo "[WARNING] The terminal helper did not start - see .orbit-bin/orbit-terminal.log" >&2
  return 1
}

case "$1" in
  start)
    echo "Building the ORBIT terminal helper for $OS/$ARCH (inside Docker)..."
    mkdir -p .orbit-bin
    if ! docker build -q --target export --build-arg AGENT_OS="$OS" --build-arg AGENT_ARCH="$ARCH" \
         --output type=local,dest=.orbit-bin/build terminal-agent >/dev/null; then
      echo "[WARNING] The terminal helper could not be built - the Terminal panel will not work. Is Docker running?" >&2
      [ -x "$BIN" ] || exit 1
      ping || launch
      exit $?
    fi
    kill_helper
    cp .orbit-bin/build/orbit-terminal "$BIN" && chmod +x "$BIN" || exit 1
    launch ;;
  ensure)
    [ -x "$BIN" ] || { echo "The terminal helper is not built yet - run ./scripts/orbit-terminal.sh start"; exit 1; }
    if ping; then echo "ORBIT terminal helper is running."; exit 0; fi
    launch ;;
  stop)
    kill_helper
    echo "ORBIT terminal helper stopped." ;;
  status)
    if ping; then echo "ORBIT terminal helper is running on http://127.0.0.1:8091"; else echo "ORBIT terminal helper is not running."; exit 1; fi ;;
  run)
    [ -x "$BIN" ] || "$0" start >/dev/null
    kill_helper
    exec "$ROOT/$BIN" --root "$ROOT" ;;
  *)
    echo "Usage: ./scripts/orbit-terminal.sh start | ensure | stop | status | run" >&2
    exit 1 ;;
esac
