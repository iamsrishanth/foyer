#!/system/bin/sh
# Supporting diagnostic only: drives Foyer's own API without printing the pairing token.
# Usage:
#   foyer-api.sh cmd <left|right|up|down|ok|noop>     -> POST /api/cmd
#   foyer-api.sh global <back|home|recents>           -> POST /api/global
#   foyer-api.sh state                                -> GET  /api/state
#   foyer-api.sh raw <path> <key> <value>             -> POST with one string field
TOKF=/data/data/com.kaera.foyer/shared_prefs/foyer.xml

token() {
  run-as com.kaera.foyer cat "$TOKF" 2>/dev/null \
    | sed -n 's/.*<string name="token">\([0-9a-f]*\)<\/string>.*/\1/p' | head -1
}

post() {
  p="$1"; body="$2"
  tok=$(token)
  [ -z "$tok" ] && { echo "ERR no-token"; exit 1; }
  case "$body" in
    "{}") body="{\"token\":\"$tok\"}" ;;
    *) body=$(printf '%s' "$body" | sed "s/}$/,\"token\":\"$tok\"}/") ;;
  esac
  len=${#body}
  printf 'POST %s HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
    "$p" "$len" "$body" | /system/bin/nc -W 4 127.0.0.1 8484 | tail -1
}

get() {
  printf 'GET %s HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' "$1" \
    | /system/bin/nc -W 4 127.0.0.1 8484 | tail -1
}

kind="$1"
case "$kind" in
  cmd)    post /api/cmd "{\"cmd\":\"$2\"}" ;;
  global) post /api/global "{\"action\":\"$2\"}" ;;
  raw)    post "$2" "{\"$3\":\"$4\"}" ;;
  state)  get /api/state ;;
  *)      echo "usage: foyer-api.sh cmd|global|state|raw ..." ;;
esac
