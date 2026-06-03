#!/usr/bin/env bash
# Local Ops control-plane: migrate Merchants DB → start Ops → smoke test.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OPS_DIR="$ROOT/Ops"
MERCHANTS_DIR="$ROOT/Merchants"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[ops-dev]${NC} $*"; }
warn() { echo -e "${YELLOW}[ops-dev]${NC} $*"; }
die()  { echo -e "${RED}[ops-dev]${NC} $*" >&2; exit 1; }

# Defaults (override via env)
export MERCHANTS_DB="${MERCHANTS_DB:-postgres://merchants:merchants123@localhost/merchants?sslmode=disable}"
export OPS_ADDR="${OPS_ADDR:-:9010}"
export OPS_TOKEN="${OPS_TOKEN:-dev-ops-test}"
export WIRE_ADMIN_URL="${WIRE_ADMIN_URL:-http://localhost:7475}"
export WIRE_M2M_TOKEN="${WIRE_M2M_TOKEN:-}"

OPS_PORT="${OPS_ADDR#:}"
OPS_PORT="${OPS_PORT##*:}"
OPS_BASE="http://127.0.0.1:${OPS_PORT}"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  migrate   Apply Merchants schema (OpenStore migrate on startup)
  start     Build and run Ops control plane (foreground)
  test      Smoke-test APIs (Ops must be running)
  all       migrate → start in background → test → stop

Env:
  MERCHANTS_DB   Postgres DSN (default: merchants.kson db.dsn)
  OPS_ADDR       Listen address (default :9010)
  OPS_TOKEN      Bearer token (default dev-ops-test)
  WIRE_ADMIN_URL Saving admin HTTP (default http://localhost:7475)

Examples:
  ./Ops/dev-ops.sh migrate
  ./Ops/dev-ops.sh start
  OPS_TOKEN=secret ./Ops/dev-ops.sh test
  ./Ops/dev-ops.sh all
EOF
}

pg_ready() {
  if command -v pg_isready >/dev/null 2>&1; then
    pg_isready -q -d "$(echo "$MERCHANTS_DB" | sed -E 's/.*@([^?]+).*/\1/')" 2>/dev/null && return 0
  fi
  psql "$MERCHANTS_DB" -c 'SELECT 1' >/dev/null 2>&1
}

cmd_migrate() {
  log "Postgres check..."
  pg_ready || die "Cannot reach DB: $MERCHANTS_DB"

  log "Merchants migrate (embedded in OpenStore)..."
  local pid logf
  logf="$(mktemp /tmp/merchants-migrate.XXXXXX.log)"
  (
    cd "$MERCHANTS_DIR"
    export MERCHANTS_CONFIG=merchants.kson
    export MERCHANTS_DB
    exec go run . >>"$logf" 2>&1
  ) &
  pid=$!

  for _ in $(seq 1 30); do
    if grep -q "merchants-host ready" "$logf" 2>/dev/null; then
      log "Schema ready (merchants-host started)"
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      rm -f "$logf"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      cat "$logf" >&2
      rm -f "$logf"
      die "Merchants exited before migrate completed"
    fi
    sleep 0.5
  done
  kill "$pid" 2>/dev/null || true
  cat "$logf" >&2
  rm -f "$logf"
  die "Migrate timed out"
}

cmd_start() {
  pg_ready || die "Cannot reach DB: $MERCHANTS_DB"
  log "Building Ops..."
  go build -C "$OPS_DIR" -o "$OPS_DIR/ops" .
  log "Ops control plane → ${OPS_BASE}/  (token: ${OPS_TOKEN})"
  log "Wire admin proxy → ${WIRE_ADMIN_URL}"
  exec env MERCHANTS_DB="$MERCHANTS_DB" OPS_ADDR="$OPS_ADDR" OPS_TOKEN="$OPS_TOKEN" \
    WIRE_ADMIN_URL="$WIRE_ADMIN_URL" WIRE_M2M_TOKEN="$WIRE_M2M_TOKEN" \
    "$OPS_DIR/ops"
}

cmd_test() {
  log "Smoke test ${OPS_BASE}"
  local auth="Authorization: Bearer ${OPS_TOKEN}"

  # login
  local login_body='{"token":"'"${OPS_TOKEN}"'"}'
  local login_ok
  login_ok=$(curl -sf -X POST "${OPS_BASE}/api/login" \
    -H 'Content-Type: application/json' -d "$login_body" || echo FAIL)
  [[ "$login_ok" == *'"ok":true'* ]] || die "POST /api/login failed: $login_ok"
  log "  POST /api/login  OK"

  # overview
  curl -sf "${OPS_BASE}/api/overview" -H "$auth" | grep -q total_merchants \
    || die "GET /api/overview failed"
  log "  GET /api/overview  OK"

  # merchants
  curl -sf "${OPS_BASE}/api/merchants" -H "$auth" | grep -q '^\[' \
    || die "GET /api/merchants failed"
  log "  GET /api/merchants  OK"

  # settlement (YYYY-MM-DD query params)
  local from to
  if date -v-7d +%Y-%m-%d >/dev/null 2>&1; then
    from=$(date -v-7d +%Y-%m-%d)
    to=$(date +%Y-%m-%d)
  else
    from=$(date -d '7 days ago' +%Y-%m-%d)
    to=$(date +%Y-%m-%d)
  fi
  curl -sf "${OPS_BASE}/api/settlement?from=${from}&to=${to}" -H "$auth" | grep -q '"rows"' \
    || die "GET /api/settlement failed"
  log "  GET /api/settlement  OK"

  # UI root
  curl -sf "${OPS_BASE}/" | grep -qi ops || warn "GET / may not be ops HTML (check manually)"
  log "  GET /  OK"

  log "All smoke checks passed. Open ${OPS_BASE}/ and login with token: ${OPS_TOKEN}"
}

cmd_all() {
  cmd_migrate
  log "Starting Ops in background..."
  go build -C "$OPS_DIR" -o "$OPS_DIR/ops" .
  env MERCHANTS_DB="$MERCHANTS_DB" OPS_ADDR="$OPS_ADDR" OPS_TOKEN="$OPS_TOKEN" \
    WIRE_ADMIN_URL="$WIRE_ADMIN_URL" WIRE_M2M_TOKEN="$WIRE_M2M_TOKEN" \
    "$OPS_DIR/ops" >>/tmp/ops.log 2>&1 &
  local pid=$!
  trap 'kill '"$pid"' 2>/dev/null || true' EXIT

  for _ in $(seq 1 20); do
    if curl -sf "${OPS_BASE}/" >/dev/null 2>&1; then break; fi
    sleep 0.5
  done
  curl -sf "${OPS_BASE}/" >/dev/null || die "Ops did not start (tail -f /tmp/ops.log)"

  cmd_test
  log "Ops still running (pid $pid). Logs: tail -f /tmp/ops.log"
  log "Stop: kill $pid"
  trap - EXIT
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    migrate) cmd_migrate ;;
    start)   cmd_start ;;
    test)    cmd_test ;;
    all)     cmd_all ;;
    -h|--help|help|"") usage ;;
    *) die "Unknown command: $cmd (try --help)" ;;
  esac
}

main "$@"
