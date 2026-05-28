#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="root@5.104.83.76"
REMOTE_DIR="/root/nivic-dev"
M2M_TOKEN="03a37ed9ebc2ad037781d40833da5d1b761988813d7068358525e7e1e0c41b90"
OPS_TOKEN="${OPS_TOKEN:-ops-$(echo -n "$M2M_TOKEN" | sha256sum | cut -c1-32)}"
SMTP_PASS="${SMTP_PASS:-EmailPassword10}"
JWT_SECRET="${JWT_SECRET:-$(echo -n "jwt-${M2M_TOKEN}" | sha256sum | cut -c1-64)}"
OSS_ENDPOINT="${OSS_ENDPOINT:-}"
OSS_BUCKET="${OSS_BUCKET:-}"
OSS_REGION="${OSS_REGION:-oss-cn-hangzhou}"
OSS_ACCESS_KEY="${OSS_ACCESS_KEY:-}"
OSS_SECRET_KEY="${OSS_SECRET_KEY:-}"

echo "==> Building Merchants (linux/amd64)..."
cd "$SCRIPT_DIR/Merchants"
GOOS=linux GOARCH=amd64 go build -o merchants-linux .
cd ..

echo "==> Building Ops (linux/amd64)..."
cd "$SCRIPT_DIR/Ops"
GOOS=linux GOARCH=amd64 go build -o ops-linux .
cd ..

echo "==> Building IAM (linux/amd64)..."
cd "$SCRIPT_DIR/IAM"
GOOS=linux GOARCH=amd64 go build -o iam-linux .
cd ..

echo "==> Building GoProxy (linux/amd64)..."
cd "$HOME/fluxor-runtime/apps/go-proxy"
GOOS=linux GOARCH=amd64 go build -o goproxy-linux .
cd "$SCRIPT_DIR"

echo "==> Building saving-gateway (linux/amd64)..."
cd "$SCRIPT_DIR/saving-gateway"
GOOS=linux GOARCH=amd64 go build -o saving-gateway-linux .
GOOS=linux GOARCH=amd64 go build -o gateway-subprocess-linux ./cmd/gateway-subprocess/
cd "$SCRIPT_DIR"


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
scp IAM/iam-linux                                         "$SERVER:/root/app/iam-new"
scp infra/systemd/iam.service                             "$SERVER:/tmp/iam.service"
scp "$HOME/fluxor-runtime/apps/go-proxy/goproxy-linux"    "$SERVER:/root/app/goproxy-new"
sed -e "s/__OSS_ENDPOINT__/${OSS_ENDPOINT}/g" \
    -e "s/__OSS_BUCKET__/${OSS_BUCKET}/g" \
    -e "s/__OSS_REGION__/${OSS_REGION}/g" \
    -e "s/__OSS_ACCESS_KEY__/${OSS_ACCESS_KEY}/g" \
    -e "s/__OSS_SECRET_KEY__/${OSS_SECRET_KEY}/g" \
    GoProxy/goproxy.json > /tmp/goproxy-rendered.json
scp /tmp/goproxy-rendered.json                            "$SERVER:/root/app/goproxy.json"
scp infra/systemd/goproxy.service                         "$SERVER:/etc/systemd/system/goproxy.service"
scp saving-gateway/saving-gateway-linux                   "$SERVER:/root/app/saving-gateway/saving-gateway-new"
scp saving-gateway/gateway-subprocess-linux               "$SERVER:/root/app/saving-gateway/gateway-subprocess"
scp infra/systemd/saving-gateway.service                  "$SERVER:/etc/systemd/system/saving-gateway.service"
scp Caddyfile                                             "$SERVER:/etc/caddy/Caddyfile"

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

sed -e 's/__JWT_SECRET__/${JWT_SECRET}/g' \
    /tmp/iam.service \
  > /etc/systemd/system/iam.service

mv /root/app/iam-new /root/app/iam
chmod +x /root/app/iam

mv /root/app/goproxy-new /root/app/goproxy
chmod +x /root/app/goproxy
mkdir -p /var/lib/goproxy

mkdir -p /root/app/saving-gateway
mv /root/app/saving-gateway/saving-gateway-new /root/app/saving-gateway/saving-gateway
chmod +x /root/app/saving-gateway/saving-gateway
chmod +x /root/app/saving-gateway/gateway-subprocess

systemctl daemon-reload
systemctl enable saving-gateway
systemctl restart saving-gateway
systemctl restart merchants
systemctl enable ops
systemctl restart ops
systemctl enable iam
systemctl restart iam
systemctl enable goproxy
systemctl restart goproxy
caddy reload --config /etc/caddy/Caddyfile
echo "merchants status: \$(systemctl is-active merchants)"
echo "ops status:       \$(systemctl is-active ops)"
echo "iam status:       \$(systemctl is-active iam)"
echo "goproxy status:         \$(systemctl is-active goproxy)"
echo "saving-gateway status:  \$(systemctl is-active saving-gateway)"

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
