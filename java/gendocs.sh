#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DOCS="$ROOT/docs/generated"
SCHEMA="$ROOT/src/main/resources/db/schema.sql"
mkdir -p "$DOCS"

echo "=== Generating schema documentation ==="

cat > "$DOCS/schema.md" << 'MDHEAD'
# Database Schema

Generated from `src/main/resources/db/schema.sql`.

MDHEAD

while IFS= read -r line; do
  if [[ "$line" =~ ^CREATE\ TABLE\ +(IF\ NOT\ EXISTS\ +)?([a-z_]+) ]]; then
    tbl="${BASH_REMATCH[2]}"
    echo "" >> "$DOCS/schema.md"
    echo "## \`$tbl\`" >> "$DOCS/schema.md"
  elif [[ "$line" =~ ^COMMENT\ ON\ TABLE\ ([a-z_]+)\ IS\ \'(.+)\' ]]; then
    t="${BASH_REMATCH[1]}"
    c="${BASH_REMATCH[2]}"
    echo "**Description:** $c" >> "$DOCS/schema.md"
    echo "" >> "$DOCS/schema.md"
    echo "| Column | Type | Description |" >> "$DOCS/schema.md"
    echo "|--------|------|-------------|" >> "$DOCS/schema.md"
  elif [[ "$line" =~ ^COMMENT\ ON\ COLUMN\ ([a-z_]+)\.([a-z_]+)\ IS\ \'(.+)\' ]]; then
    tbl="${BASH_REMATCH[1]}"
    col="${BASH_REMATCH[2]}"
    colc="${BASH_REMATCH[3]}"
    echo "| \`$col\` | | $colc |" >> "$DOCS/schema.md"
  fi
done < "$SCHEMA"

echo "=== Generating package overview ==="

cat > "$DOCS/packages.md" << 'PKGHEAD'
# Package Overview

Generated from `src/main/java/dev/nivic`.

PKGHEAD

find "$ROOT/src/main/java/dev/nivic" -name "package-info.java" | sort | while read -r pkgfile; do
  pkg=$(dirname "$pkgfile" | sed "s|$ROOT/src/main/java/||" | tr '/' '.')
  desc=$(grep -oP '\*\*([^*]+)\*\*' "$pkgfile" 2>/dev/null || echo "")
  if grep -q "@see\|@since" "$pkgfile" 2>/dev/null; then
    desc="(documented)"
  fi
  echo "- **\`$pkg\`** $desc" >> "$DOCS/packages.md"
done

find "$ROOT/src/main/java/dev/nivic" -name "*.java" ! -name "package-info.java" | sort | while read -r src; do
  rel=$(echo "$src" | sed "s|$ROOT/src/main/java/||" | sed 's|\.java||' | tr '/' '.')
  class=$(basename "$src" .java)
  firstline=$(grep -m1 'public final class\|public record\|public interface\|public enum\|public class' "$src" 2>/dev/null || true)
  if [[ -n "$firstline" ]]; then
    echo "  - \`$class\` — \`$rel\`" >> "$DOCS/packages.md"
  fi
done

echo "=== Generating hot-path reference ==="

cat > "$DOCS/hot-path.md" << 'HPHEAD'
# Hot Path: Payment Acceptance Flow

The critical production path for every payment:

HPHEAD

cat >> "$DOCS/hot-path.md" << 'HPBODY'
```
Client → POST binary → SevletWalletPayloadServlet
  → BinaryBodyReader (cap: maxBodyBytes)
  → SevletWalletCodec.decode (wire format → payload record)
  → ExtraDataPolicy.validateLength (cap: maxExtraDataBytes)
  → WalletVerificationService.verify
    → JdbcMidSecretResolver.requireProfile(mid) → secretKey
    → SevletWalletHmac.verify(raw, secretKey) ← HMAC-SHA256
  → WalletAcceptService.claimAndPersist(raw, payload)
    → MidProfile check (enabled?)
    → IdempotencyGate.claimFirst(mid, requestId, orderId, orderPaymentMode)
    → [if duplicate] return 409 CONFLICT
    → WalService.append(raw)  ← crash-safe WAL first
    → [if immediate settle]
      → LedgerService.record(payload, currency)
        → WalletLedger.append(payload, currency)
        → WalletJournal.append(payload, currency)  ← double-entry
      → PaymentLedger.appendAfterWallet(payload, currency)
    → [if order intent]
      → PaymentLedger.append(payload, currency, ctx)  ← intent with challenge
      → return challenge in JSON
    → [if confirm]
      → ConfirmPayloadParser.validateExtra(extraData)
      → LedgerService.record(payload, currency)
      → PaymentLedger.settleIntentByOrder(payload, currency)
  → JSON response (200 OK)
```

## Test Commands

| Command | Purpose |
|---------|---------|
| `./build.sh` | Full pipeline: gencode → hot-path tests → all tests → gendocs → package |
| `mvn test -Dtest.groups=hot-path` | Only hot-path tests (fast feedback) |
| `mvn test` | All tests |
| `./gen.sh` | Regenerate Java constants from SQL schema |
| `./gendocs.sh` | Regenerate documentation |

## Tag Hierarchy

- `@Tag("hot-path")` → critical payment flow tests (run FIRST)
- All other tests → unit/component tests (run after hot-path passes)
HPBODY

echo "Generated docs in $DOCS/"
