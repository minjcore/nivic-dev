#!/usr/bin/env bash
set -euo pipefail

SERVER="root@5.104.83.76"
REMOTE_DIR="/root/nivic-dev"
M2M_TOKEN="03a37ed9ebc2ad037781d40833da5d1b761988813d7068358525e7e1e0c41b90"
SMTP_PASS="${SMTP_PASS:-EmailPassword10}"

echo "==> Building Merchants (linux/amd64)..."
cd "$(dirname "$0")/Merchants"
GOOS=linux GOARCH=amd64 go build -o merchants-linux .
cd ..

echo "==> Syncing source to server..."
rsync -az --exclude '.build' --exclude '*.db' \
  saving/         "$SERVER:$REMOTE_DIR/saving/"
rsync -az \
  docker-compose.prod.yml \
  "$SERVER:$REMOTE_DIR/docker-compose.prod.yml"

echo "==> Uploading Merchants binary + config + service file..."
scp Merchants/merchants-linux          "$SERVER:/root/app/merchants-new"
scp Merchants/merchants.kson           "$SERVER:/root/app/merchants.kson"
scp infra/systemd/merchants.service    "$SERVER:/tmp/merchants.service"

echo "==> Deploying on server..."
ssh "$SERVER" bash <<ENDSSH
set -euo pipefail

# ── Merchants service ────────────────────────────────────────────────────────
sed -e 's/__M2M_TOKEN__/${M2M_TOKEN}/g' \
    -e 's/__SMTP_PASS__/${SMTP_PASS}/g' \
    /tmp/merchants.service \
  > /etc/systemd/system/merchants.service

mv /root/app/merchants-new /root/app/merchants
chmod +x /root/app/merchants

systemctl daemon-reload
systemctl restart merchants
echo "merchants status: \$(systemctl is-active merchants)"

# ── Wire .env ────────────────────────────────────────────────────────────────
cd $REMOTE_DIR

# Add or update WIRE_M2M_TOKEN in .env
if grep -q '^WIRE_M2M_TOKEN=' .env 2>/dev/null; then
  sed -i 's|^WIRE_M2M_TOKEN=.*|WIRE_M2M_TOKEN=${M2M_TOKEN}|' .env
else
  echo 'WIRE_M2M_TOKEN=${M2M_TOKEN}' >> .env
fi

# ── Wire Docker ──────────────────────────────────────────────────────────────
docker compose -f docker-compose.prod.yml build wire
docker compose -f docker-compose.prod.yml up -d wire

echo "wire status: \$(docker compose -f docker-compose.prod.yml ps wire --format '{{.State}}')"

# ── Smoke test ───────────────────────────────────────────────────────────────
sleep 3
HTTP=\$(curl -s -o /dev/null -w '%{http_code}' \
  -H 'X-M2M-Token: ${M2M_TOKEN}' \
  'http://localhost:7475/api/txn?id=1')
echo "Wire admin /api/txn smoke test: HTTP \$HTTP (404 = ok, txn 1 not found)"

HTTP2=\$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST http://localhost:8090/orders/SMOKE_TEST/wire_confirm \
  -H 'Content-Type: application/json' \
  -d '{"txn_id":1,"paid_by":16777216}')
echo "Merchants /wire_confirm smoke test: HTTP \$HTTP2 (404 = ok, order not found)"
ENDSSH

echo ""
echo "==> Deploy xong!"
echo "    M2M token: ${M2M_TOKEN}"
