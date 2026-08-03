#!/usr/bin/env bash
# Локальный сид: четыре входа, стол по RRC-RU, посадка и старт партии.
# Работает только с профилем local — код входа берётся из лога контейнера.
set -euo pipefail

PORT="${FRONTEND_PORT:-5173}"
BASE="http://localhost:${PORT}/api/v1"
RUN_ID="${SEED_RUN_ID:-$(date +%s)}"
JARS="$(mktemp -d)"
trap 'rm -rf "$JARS"' EXIT

email() {
  printf 'seed-%s-p%s@dorahub.local' "$RUN_ID" "$1"
}

csrf() {
  curl -s -b "$1" -c "$1" "$BASE/auth/csrf" |
    python3 -c "import sys,json;print(json.load(sys.stdin)['token'])"
}

login() {
  local email=$1 jar=$2 token code
  token=$(curl -s -c "$jar" "$BASE/auth/csrf" |
    python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
  curl -sf -o /dev/null -b "$jar" -c "$jar" -X POST "$BASE/auth/email/code" \
    -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $token" \
    -d "{\"email\":\"$email\"}"
  code=$(docker compose logs backend 2>&1 |
    grep "код входа для $email:" | tail -1 | sed 's/.*: \([0-9]\{6\}\).*/\1/')
  [ -n "$code" ] || { echo "не нашёл код для $email — запущен ли профиль local?" >&2; exit 1; }
  curl -sf -o /dev/null -b "$jar" -c "$jar" -X POST "$BASE/auth/email/verify" \
    -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $(csrf "$jar")" \
    -d "{\"email\":\"$email\",\"code\":\"$code\"}"
}

post() {
  local jar=$1 path=$2
  if [ $# -ge 3 ]; then
    curl -sf -b "$jar" -c "$jar" -X POST "$BASE$path" \
      -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $(csrf "$jar")" -d "$3"
  else
    curl -sf -b "$jar" -c "$jar" -X POST "$BASE$path" -H "X-CSRF-TOKEN: $(csrf "$jar")"
  fi
}

for i in 1 2 3 4; do
  login "$(email "$i")" "$JARS/j$i"
  echo "вошёл $(email "$i")"
done

table=$(post "$JARS/j1" /tables '{"rulesetKey":"rrc-ru","format":"HANCHAN"}' |
  python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

for i in 2 3 4; do post "$JARS/j$i" "/tables/$table/players" > /dev/null; done
post "$JARS/j1" "/tables/$table/start" |
  python3 -c "import sys,json;d=json.load(sys.stdin);print('партия:',d['state'],d['roundWind'],d['handNumber'],d['scores'])"

echo
echo "Стол готов: http://localhost:${PORT}/table/${table}"
echo "Войти в браузере: $(email 1), код — из 'docker compose logs backend | grep \"код входа\"'"
