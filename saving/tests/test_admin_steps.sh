#!/usr/bin/env bash
# Saving Admin (:7475) — step-by-step smoke tests. Run: ./test_admin_steps.sh
set -euo pipefail
BASE="${ADMIN_URL:-http://127.0.0.1:7475}"
USER="${ADMIN_USER:-admin}"
PASS="${ADMIN_PASS:-saving_admin_dev}"

step() { echo ""; echo "━━━ $1 ━━━"; }

curl_json() {
  local method="$1" path="$2" body="${3:-}"
  local auth="${4:-}"
  local args=(-s -w "\nHTTP:%{http_code}" -X "$method" -H "Content-Type: application/json")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer $auth")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" "$BASE$path"
}

step "1. Login OK"
R=$(curl_json POST /api/login "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
echo "$R" | head -1
TOKEN=$(echo "$R" | head -1 | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
echo "token_len=${#TOKEN}"

step "2. Login FAIL (bad password)"
curl_json POST /api/login '{"username":"admin","password":"wrong"}' | head -1

step "3. Stats (dashboard)"
curl_json GET /api/stats "" "$TOKEN" | sed 's/HTTP:/\nHTTP:/'

step "4. Maintenance GET"
curl_json GET /api/maintenance "" "$TOKEN" | sed 's/HTTP:/\nHTTP:/'

step "5. Maintenance ON → GET"
curl_json POST /api/maintenance '{"enabled":true}' "$TOKEN" | sed 's/HTTP:/\nHTTP:/'
curl_json GET /api/maintenance "" "$TOKEN" | sed 's/HTTP:/\nHTTP:/'

step "6. Maintenance OFF (restore)"
curl_json POST /api/maintenance '{"enabled":false}' "$TOKEN" | sed 's/HTTP:/\nHTTP:/'

step "7. Sessions list"
curl_json GET /api/sessions "" "$TOKEN" | head -c 400
echo "..."

step "8. No auth → 401"
curl_json GET /api/stats "" | sed 's/HTTP:/\nHTTP:/'

step "9. Account lookup (uid=1 bank float if exists)"
curl_json GET "/api/account?uid=1" "" "$TOKEN" | head -c 500
echo ""

step "10. Audit log (last entries)"
curl_json GET /api/audit "" "$TOKEN" | head -c 600
echo "..."

echo ""
echo "✓ Admin step tests finished (Saving :7475)"
