#!/usr/bin/env bash
# Sevlet Wallet (Java): migrate Postgres → start server → smoke / mvn test.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[java-dev]${NC} $*"; }
warn() { echo -e "${YELLOW}[java-dev]${NC} $*"; }
die()  { echo -e "${RED}[java-dev]${NC} $*" >&2; exit 1; }

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

export JDBC_URL="${JDBC_URL:-jdbc:postgresql://127.0.0.1:5432/nivic?sslmode=disable}"
export JDBC_USER="${JDBC_USER:-merchants}"
export JDBC_PASSWORD="${JDBC_PASSWORD:-merchants123}"

MVN=(mvn)
if ! command -v mvn >/dev/null 2>&1; then
  MVN=("${HOME}/.local/opt/apache-maven-3.9.9/bin/mvn")
fi

jdbc_to_psql() {
  local hostport db path
  path="${JDBC_URL#jdbc:postgresql://}"
  path="${path%%\?*}"
  if [[ "$path" == *"@"* ]]; then
    die "Set JDBC_URL without embedded user (use JDBC_USER / JDBC_PASSWORD)"
  fi
  if [[ "$path" == */* ]]; then
    hostport="${path%%/*}"
    db="${path#*/}"
  else
    hostport="$path"
    db=""
  fi
  if [[ -z "$db" ]]; then die "JDBC_URL must include database name"; fi
  echo "postgresql://${JDBC_USER}:${JDBC_PASSWORD}@${hostport}/${db}"
}

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  memory    Start in-memory Undertow (:8080, no Postgres) — foreground
  migrate   Apply src/main/resources/db/schema.sql + seed mid=1
  start     Tomcat + JDBC (needs .env) — foreground via cargo:run
  test      mvn hot-path tests (no DB)
  test-coa  COA fund-flow integration tests (Testcontainers Postgres)
  test-all  Full mvn test
  test-ship WAL shipper unit tests
  ship      WAL → NDJSON (analytics Phase 1 sidecar, one pass)
  smoke     HTTP smoke (server must be on :8080)
  all       migrate → start JDBC in background → smoke → stop

Quick (no DB):
  ./dev-java.sh memory          # terminal 1
  ./dev-java.sh test            # terminal 2

With Postgres (create DB first: createdb nivic):
  cp .env.example .env          # edit JDBC_* if needed
  ./dev-java.sh all

Env: JDBC_URL, JDBC_USER, JDBC_PASSWORD (see .env.example)
EOF
}

cmd_migrate() {
  local psql_dsn
  psql_dsn="$(jdbc_to_psql)"
  command -v psql >/dev/null 2>&1 || die "psql not found"

  log "Postgres → $(echo "$psql_dsn" | sed -E 's/:([^:@/]+)@/:***@/')"
  if ! psql "$psql_dsn" -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
    local admin_dsn dbname
    dbname="${psql_dsn##*/}"
    admin_dsn="${psql_dsn%/*}/postgres"
    if psql "$admin_dsn" -v ON_ERROR_STOP=1 -c "SELECT 1" >/dev/null 2>&1; then
      warn "Database '$dbname' missing — creating..."
      if psql "$admin_dsn" -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"${dbname}\" OWNER \"${JDBC_USER}\";" 2>/dev/null \
        || psql "$admin_dsn" -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"${dbname}\";" 2>/dev/null; then
        :
      else
        die "Create database first: createdb -O ${JDBC_USER} ${dbname}"
      fi
    else
      die "Cannot connect to $dbname or postgres admin. Fix JDBC_* in .env"
    fi
  fi

  log "Applying schema.sql (led_*, acct_*, coa_*, sav_*, …)..."
  psql "$psql_dsn" -v ON_ERROR_STOP=1 -f "$ROOT/src/main/resources/db/schema.sql"

  log "Seeding mid=1 (dev HMAC key all-zero)..."
  psql "$psql_dsn" -v ON_ERROR_STOP=1 -f "$ROOT/src/main/resources/db/seed/01_first_mid.sql"

  log "Migrate done."
}

cmd_memory() {
  exec "$ROOT/dev-start.sh"
}

cmd_start() {
  [[ -n "${JDBC_URL:-}" ]] || die "Set JDBC_URL in .env"
  exec "$ROOT/dev-server.sh"
}

cmd_test() {
  log "Hot-path tests (in-memory, no DB)..."
  "${MVN[@]}" -q test -Dgroups=hot-path
  log "Hot-path OK"
}

cmd_test_coa() {
  log "COA integration tests..."
  "${MVN[@]}" -q test -Dtest=TopUpFlowTest,WithdrawFlowTest,InternalTransferFlowTest,IbftFlowTest,QrPosFlowTest,EodSettlementFlowTest
  log "COA tests OK"
}

cmd_test_all() {
  "${MVN[@]}" test
}

cmd_test_ship() {
  log "WAL shipper tests..."
  "${MVN[@]}" -q test -Dtest=WalShipperTest
  log "WalShipper OK"
}

cmd_ship() {
  local wal="${WAL_PATH:-${TMPDIR:-/tmp}/sevlet-wallet.wal}"
  local out="${ANALYTICS_OUT:-${TMPDIR:-/tmp}/nivic-wallet-events.ndjson}"
  local cursor="${WAL_SHIPPER_CURSOR:-${out}.cursor}"
  local extra=()
  [[ -n "${WAL_PUBKEY_DER:-}" ]] && extra+=(--pubkey "$WAL_PUBKEY_DER")
  [[ -n "${WAL_SHIP_CURRENCY:-}" ]] && extra+=(--currency "$WAL_SHIP_CURRENCY")
  [[ "${WAL_SHIP_SYNC:-}" == "1" ]] && extra+=(--sync)

  log "WAL shipper: $wal → $out"
  "${MVN[@]}" -q exec:java \
    -Dexec.mainClass=dev.nivic.cli.WalShipperMain \
    -Dexec.classpathScope=compile \
    -Dexec.args="--wal ${wal} --out ${out} --cursor ${cursor} ${extra[*]:-}" \
    2>&1 | grep -v "^WARNING:" || true
}

cmd_smoke() {
  local base="${JAVA_DEV_BASE:-http://127.0.0.1:8080}"
  log "Smoke ${base}"

  # dynamic-form manifest (always registered on full WAR; memory dev may 404 — warn only)
  if curl -sf "${base}/api/dynamic-form/manifest" >/dev/null 2>&1; then
    log "  GET /api/dynamic-form/manifest  OK"
  else
    warn "  GET /api/dynamic-form/manifest  skip (memory dev server has wallet only)"
  fi

  # binary wallet TRANSFER (mid=1, zero HMAC key when midSecretMode=skip or seeded mid)
  python3 - "$base" <<'PY'
import struct, sys, urllib.request

base = sys.argv[1].rstrip("/")
# pad(3) cmd mid req order amt debit credit extra sig
body = struct.pack(">3x qqqqq ii", 0, 1, 1001, 2001, 50000, 1, 2)
body += b"\x00" * 32  # sig (skip mode accepts)

req = urllib.request.Request(
    f"{base}/sevlet/wallet/payload",
    data=body,
    method="POST",
    headers={"Content-Type": "application/octet-stream"},
)
try:
    resp = urllib.request.urlopen(req, timeout=10)
    data = resp.read()
    print(data[:200].decode("utf-8", errors="replace"))
    if resp.status != 200:
        sys.exit(1)
except urllib.error.HTTPError as e:
    print(e.read()[:300].decode("utf-8", errors="replace"), file=sys.stderr)
    sys.exit(1)
PY
  log "  POST /sevlet/wallet/payload  OK"
}

cmd_all() {
  cmd_migrate
  log "Building WAR + starting Tomcat (background)..."
  if [[ -f .env ]]; then set -a; source .env; set +a; fi
  "${MVN[@]}" -q clean package -DskipTests
  nohup "${MVN[@]}" -q cargo:run >>/tmp/java-wallet.log 2>&1 &
  local pid=$!
  trap 'kill '"$pid"' 2>/dev/null || true' EXIT

  for _ in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:8080/api/dynamic-form/manifest" >/dev/null 2>&1; then break; fi
    if curl -sf -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:8080/sevlet/wallet/payload" \
         -H 'Content-Type: application/octet-stream' --data-binary @/dev/null 2>/dev/null | grep -qE '^(200|400|401)'; then
      break
    fi
    sleep 1
  done

  cmd_smoke
  log "Server still running (pid $pid). Logs: tail -f /tmp/java-wallet.log"
  log "UI: http://127.0.0.1:8080/webapp/dynamic-form/index.html"
  log "Stop: kill $pid"
  trap - EXIT
}

main() {
  case "${1:-}" in
    memory)    cmd_memory ;;
    migrate)   cmd_migrate ;;
    start)     cmd_start ;;
    test)      cmd_test ;;
    test-coa)  cmd_test_coa ;;
    test-all)  cmd_test_all ;;
    test-ship) cmd_test_ship ;;
    ship)      cmd_ship ;;
    smoke)     cmd_smoke ;;
    all)       cmd_all ;;
    -h|--help|help|"") usage ;;
    *) die "Unknown: $1 (try --help)" ;;
  esac
}

main "$@"
