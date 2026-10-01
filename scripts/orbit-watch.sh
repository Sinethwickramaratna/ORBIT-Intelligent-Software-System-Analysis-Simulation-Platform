#!/bin/sh
# ORBIT host helper (started by start.sh). When you change the folders in ORBIT's Settings the app cannot re-create
# its own Docker containers, so it leaves a small request file in ./.orbit-signal. This script, running on your
# computer, sees it and runs `docker compose up -d` (no rebuild; your data is kept).
# It also touches ./.orbit-signal/watcher every few seconds so the app knows it can apply changes by itself.
#
#   ./scripts/orbit-watch.sh             run the helper (start.sh does this for you)
#   ./scripts/orbit-watch.sh stop        stop it
#   ./scripts/orbit-watch.sh install     start it automatically when you sign in (macOS LaunchAgent, Linux systemd
#                                        user service or desktop autostart entry)
#   ./scripts/orbit-watch.sh uninstall   remove that again
#   ./scripts/orbit-watch.sh installed   exit code 0 when the automatic start is set up
ROOT="$(cd "$(dirname "$0")/.." && pwd)" || exit 1
SELF="$ROOT/scripts/orbit-watch.sh"
cd "$ROOT" || exit 1
SIG=.orbit-signal
PIDFILE="$SIG/watcher.pid"
PLIST="$HOME/Library/LaunchAgents/com.orbit.helper.plist"
UNIT="$HOME/.config/systemd/user/orbit-helper.service"
DESKTOP="$HOME/.config/autostart/orbit-helper.desktop"
OS="$(uname -s)"

have_systemd_user() { command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; }

case "$1" in
  stop)
    [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null
    rm -f "$PIDFILE" "$SIG/watcher"
    echo "ORBIT helper stopped."
    exit 0 ;;
  installed)
    [ -f "$PLIST" ] || [ -f "$UNIT" ] || [ -f "$DESKTOP" ]
    exit $? ;;
  install)
    if [ "$OS" = "Darwin" ]; then
      mkdir -p "$(dirname "$PLIST")"
      cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.orbit.helper</string>
  <key>ProgramArguments</key><array><string>/bin/sh</string><string>$SELF</string></array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/Applications/Docker.app/Contents/Resources/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
PL
      launchctl unload "$PLIST" >/dev/null 2>&1; launchctl load -w "$PLIST"
      echo "ORBIT helper will start automatically when you sign in (remove with: $SELF uninstall)."
    elif have_systemd_user; then
      mkdir -p "$(dirname "$UNIT")"
      cat > "$UNIT" <<UN
[Unit]
Description=ORBIT helper (applies folder changes by re-creating the Docker containers)

[Service]
ExecStart=/bin/sh $SELF
Restart=on-failure

[Install]
WantedBy=default.target
UN
      systemctl --user daemon-reload && systemctl --user enable --now orbit-helper.service
      echo "ORBIT helper will start automatically when you sign in (remove with: $SELF uninstall)."
    else
      mkdir -p "$(dirname "$DESKTOP")"
      cat > "$DESKTOP" <<DE
[Desktop Entry]
Type=Application
Name=ORBIT helper
Exec=/bin/sh $SELF
Terminal=false
X-GNOME-Autostart-enabled=true
DE
      echo "ORBIT helper will start automatically when you sign in to your desktop (remove with: $SELF uninstall)."
    fi
    exit 0 ;;
  uninstall)
    if [ -f "$PLIST" ]; then launchctl unload "$PLIST" >/dev/null 2>&1; rm -f "$PLIST"; fi
    if [ -f "$UNIT" ]; then
      systemctl --user disable --now orbit-helper.service >/dev/null 2>&1
      rm -f "$UNIT"; systemctl --user daemon-reload >/dev/null 2>&1
    fi
    rm -f "$DESKTOP"
    echo "Automatic start of the ORBIT helper removed."
    exit 0 ;;
esac

mkdir -p "$SIG"
# only one helper at a time
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  exit 0
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE" "$SIG/watcher"; exit 0' INT TERM EXIT

# a leftover marker from a helper that was killed in the middle of an apply
rm -f "$SIG/running"

# `docker compose up -d` can take minutes (it waits for the backend), so it runs in the background and the helper
# keeps writing its heartbeat meanwhile - otherwise ORBIT would think the helper had stopped.
while :; do
  date > "$SIG/watcher"
  if [ -f "$SIG/apply" ] && [ ! -f "$SIG/running" ]; then
    rm -f "$SIG/apply"
    : > "$SIG/running"
    (
      {
        echo "=== $(date) docker compose up -d"
        docker compose up -d
        echo "exit code: $?"
      } >> "$SIG/apply.log" 2>&1
      rm -f "$SIG/running"
    ) &
  fi
  sleep 3
done
