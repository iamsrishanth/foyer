#!/system/bin/sh
# Foyer input helper daemon.
#
# Runs in the adb shell uid (2000), which is the only unprivileged identity Android allows to inject
# key events: INJECT_EVENTS is signature-only, so Foyer's own app process gets a SecurityException
# from /system/bin/input. Nothing here needs root, and no /system file is touched.
#
# Start it with tools/provision-input-helper.ps1 (adb push + setsid nohup). It survives the adb
# session ending and an APK install (verified: same listener PID across `adb install -r`), but NOT a
# reboot or an `adb root`/`adb unroot` (adbd restart): re-run the provisioning script after those.
# Foyer detects it on its own and reports "input": {"helper": false} in /api/state so the phone can
# say what is wrong instead of silently failing.
#
# Security shape: loopback only (127.0.0.1 via nc), one command per connection, a token written by
# the Foyer app, and a five-key allowlist. No command string is ever passed to a shell.
PORT=${1:-8486}
LOG=/data/local/tmp/foyer-inputd.log

UID_NOW=$(id -u)
if [ "$UID_NOW" != "2000" ]; then
  echo "$(date) refusing to run as uid $UID_NOW: the helper needs the adb shell uid 2000" >> "$LOG"
  exit 1
fi

# Keep the log small on a low-RAM device.
if [ -f "$LOG" ]; then
  SIZE=$(wc -c < "$LOG" 2>/dev/null)
  if [ -n "$SIZE" ] && [ "$SIZE" -gt 32768 ]; then
    : > "$LOG"
  fi
fi

echo "$(date) inputd start pid=$$ port=$PORT uid=$UID_NOW" >> "$LOG"

# -L keeps the socket listening and backgrounds a handler per connection, so there is no window in
# which the port is closed (a gap here showed up as a false "helper offline" from the app's probe).
# -s 127.0.0.1 binds loopback only: verified in /proc/net/tcp as 0100007F:<port>. Without it toybox
# listens on 0.0.0.0 and the injector would be reachable from the LAN.
# The app sends one command at a time and the TV's HTTP server is single-threaded, so the per-connect
# forking cannot reorder presses.
exec /system/bin/nc -L -s 127.0.0.1 -p "$PORT" /system/bin/sh /data/local/tmp/foyer-input-handle.sh
