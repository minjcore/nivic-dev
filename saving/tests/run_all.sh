#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PASS=0; FAIL=0

for f in test_ping.py test_create_account.py test_login.py \
          test_get_balance.py test_get_history.py test_transfer.py \
          test_cash_in.py test_cash_out.py \
          test_intent_flow.py test_paid_order.py test_list_intents.py \
          test_merchant_info.py test_pay_intent.py test_totp_charge.py \
          test_enroll_totp.py test_system_offline.py \
          test_confirm_intent.py \
          test_merchant_history.py \
          test_session_renew.py; do
  result=$(python3 "$f" 2>&1)
  last=$(echo "$result" | tail -1)
  if echo "$last" | grep -qiE "PASSED|ALL PASSED"; then
    echo "✓  $f"
    ((PASS++))
  else
    echo "✗  $f  →  $last"
    echo "$result"
    ((FAIL++))
  fi
done

echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
