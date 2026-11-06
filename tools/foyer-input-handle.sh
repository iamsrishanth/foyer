#!/system/bin/sh
# Foyer input helper - one connection, one command.
#
# Protocol (loopback only): client sends "<token> <cmd>\n"; this replies "OK <cmd>" or "ERR <why>".
# Allowlisted commands: up down left right center ping. There is no way to run anything else and no
# shell is exposed: the command names map to fixed key codes.
#
# Token: written by the Foyer app into its own external files dir, readable by this (shell) uid and
# not by other apps. If the file is missing or empty every request is refused, so removing the token
# is an effective off switch.
TOKFILE=/sdcard/Android/data/com.kaera.foyer/files/input-helper.token

read -r tok cmd rest
if [ -z "$tok" ] || [ -z "$cmd" ]; then
  echo "ERR protocol"
  exit 0
fi
real=$(cat "$TOKFILE" 2>/dev/null)
if [ -z "$real" ]; then
  echo "ERR notoken"
  exit 0
fi
if [ "$tok" != "$real" ]; then
  echo "ERR auth"
  exit 0
fi

case "$cmd" in
  ping) echo "OK ping"; exit 0 ;;
  # KEYCODE_DPAD_UP / DOWN / LEFT / RIGHT / CENTER. `input keyevent` sends the down+up pair.
  up) kc=19 ;;
  down) kc=20 ;;
  left) kc=21 ;;
  right) kc=22 ;;
  center) kc=23 ;;
  *) echo "ERR cmd"; exit 0 ;;
esac

if /system/bin/input keyevent "$kc" >/dev/null 2>&1; then
  echo "OK $cmd"
else
  echo "ERR inject"
fi
