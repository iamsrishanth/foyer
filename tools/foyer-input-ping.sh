#!/system/bin/sh
# Reads the token itself and pings the helper, so the token never appears in a host command line.
TOKFILE=/sdcard/Android/data/com.kaera.foyer/files/input-helper.token
tok=$(cat "$TOKFILE" 2>/dev/null)
if [ -z "$tok" ]; then
  echo "ERR notoken ($TOKFILE missing: run Foyer once so it writes the token)"
  exit 0
fi
printf '%s ping\n' "$tok" | /system/bin/nc -W 2 127.0.0.1 8486
