#!/usr/bin/env bash
set -euo pipefail

SERVER="root@5.104.83.76"
REMOTE_DIR="/root/nivic-dev"
M2M_TOKEN="03a37ed9ebc2ad037781d40833da5d1b761988813d7068358525e7e1e0c41b90"
OPS_TOKEN="${OPS_TOKEN:-ops-$(echo -n "$M2M_TOKEN" | sha256sum | cut -c1-32)}"
SMTP_PASS="${SMTP_PASS:-EmailPassword10}"
JWT_SECRET="${JWT_SECRET:-$(echo -n "jwt-${M2M_TOKEN}" | sha256sum | cut -c1-64)}"

echo "==> Building Merchants (linux/amd64)..."
cd "$(dirname "$0")/Merchants"
GOOS=linux GOARCH=amd64 go build -o merchants-linux .
cd ..

echo "==> Building Ops (linux/amd64)..."
cd "$(dirname "$0")/Ops"
GOOS=linux GOARCH=amd64 go build -o ops-linux .
cd ..

echo "==> Syncing source to server..."
rsync -az --exclude '.build' --exclude '*.db' \
  saving/         "$SERVER:$REMOTE_DIR/saving/"
rsync -az \
  docker-compose.prod.yml \
  "$SERVER:$REMOTE_DIR/docker-compose.prod.yml"

echo "==> Uploading binaries + configs + service files..."
scp Merchants/merchants-linux          "$SERVER:/root/app/merchants-new"
scp Merchants/merchants.kson           "$SERVER:/root/app/merchants.kson"
scp infra/systemd/merchants.service    "$SERVER:/tmp/merchants.service"
scp Ops/ops-linux                      "$SERVER:/root/app/ops-new"
scp infra/systemd/ops.service          "$SERVER:/tmp/ops.service"

echo "==> Deploying on server..."
ssh "$SERVER" bash <<ENDSSH
set -euo pipefail

# ── Merchants service ────────────────────────────────────────────────────────
sed -e 's/__M2M_TOKEN__/${M2M_TOKEN}/g' \
    -e 's/__OPS_TOKEN__/${OPS_TOKEN}/g' \
    -e 's/__SMTP_PASS__/${SMTP_PASS}/g' \
    -e 's/__JWT_SECRET__/${JWT_SECRET}/g' \
    /tmp/merchants.service \
  > /etc/systemd/system/merchants.service

mv /root/app/merchants-new /root/app/merchants
chmod +x /root/app/merchants

sed -e 's/__M2M_TOKEN__/${M2M_TOKEN}/g' \
    -e 's/__OPS_TOKEN__/${OPS_TOKEN}/g' \
    /tmp/ops.service \
  > /etc/systemd/system/ops.service

mv /root/app/ops-new /root/app/ops
chmod +x /root/app/ops

systemctl daemon-reload
systemctl restart merchants
systemctl enable ops
systemctl restart ops
echo "merchants status: \$(systemctl is-active merchants)"
echo "ops status:       \$(systemctl is-active ops)"

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
echo "    OPS token: ${OPS_TOKEN}"
echo "    Control plane: https://ops.nivic.dev/"
