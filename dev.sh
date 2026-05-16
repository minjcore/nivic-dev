#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

log()  { echo -e "${GREEN}[dev]${NC} $*"; }
warn() { echo -e "${YELLOW}[dev]${NC} $*"; }
die()  { echo -e "${RED}[dev]${NC} $*" >&2; exit 1; }

cleanup() {
    warn "Shutting down..."
    kill "${PIDS[@]}" 2>/dev/null || true
    wait 2>/dev/null || true
    log "Done."
}
trap cleanup EXIT INT TERM

PIDS=()

# ── RabbitMQ (must be running externally) ─────────────────────────────────────
if ! nc -z localhost 5672 2>/dev/null; then
    warn "RabbitMQ not detected on :5672 — start it with:"
    warn "  brew services start rabbitmq"
    warn "  or: docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management"
    die "RabbitMQ required"
fi
log "RabbitMQ   :5672  ✓"

# ── Wire (C server, port 7474) ────────────────────────────────────────────────
log "Building Wire server..."
make -C "$ROOT/saving" -s
log "Starting Wire server on :7474"
"$ROOT/saving/saving" >> /tmp/saving.log 2>&1 &
PIDS+=($!)

# ── Merchants Host (Go, port 8090) ────────────────────────────────────────────
log "Building Merchants Host..."
go build -C "$ROOT/Merchants" -o merchants .
log "Starting Merchants Host on :8090"
"$ROOT/Merchants/merchants" >> /tmp/merchants.log 2>&1 &
PIDS+=($!)

# ── Cards Service (Go, port 8091) ─────────────────────────────────────────────
log "Building Cards service..."
go build -C "$ROOT/Cards" -o cards .
log "Starting Cards service on :8091"
"$ROOT/Cards/cards" >> /tmp/cards.log 2>&1 &
PIDS+=($!)

# ── Topup Worker (Go, RabbitMQ → Wire) ────────────────────────────────────────
log "Building Topup Worker..."
go build -C "$ROOT/TopupWorker" -o topup-worker .
log "Starting Topup Worker"
"$ROOT/TopupWorker/topup-worker" >> /tmp/topup-worker.log 2>&1 &
PIDS+=($!)

# ── Wait for readiness ────────────────────────────────────────────────────────
sleep 2

nc -z localhost 7474 && log "Wire         :7474  ✓" || warn "Wire         :7474  ✗"
curl -sf http://localhost:8090/health > /dev/null && log "Merchants    :8090  ✓" || warn "Merchants    :8090  ✗"
curl -sf http://localhost:8091/health > /dev/null && log "Cards        :8091  ✓" || warn "Cards        :8091  ✗"
nc -z localhost 5672 && log "RabbitMQ     :5672  ✓" || warn "RabbitMQ     :5672  ✗"
log "TopupWorker  background ✓"

echo ""
echo "  Logs:  tail -f /tmp/saving.log /tmp/merchants.log /tmp/cards.log /tmp/topup-worker.log"
echo "  Stop:  Ctrl-C"
echo ""

wait
