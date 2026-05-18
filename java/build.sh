#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if command -v mvn >/dev/null 2>&1; then
  MVN=(mvn)
else
  MVN=("${HOME}/.local/opt/apache-maven-3.9.9/bin/mvn")
fi

BOLD='\033[1m'
DIM='\033[2m'
GREEN='\033[32m'
RED='\033[31m'
YELLOW='\033[33m'
NC='\033[0m'

step() {
  echo ""
  echo -e "${BOLD}── ${1} ──${NC}"
}

ok() {
  echo -e "  ${GREEN}✓${NC} ${1}"
}

fail() {
  echo -e "  ${RED}✗${NC} ${1}"
  exit 1
}

# ---- Phase 1: Code Generation ----
step "Phase 1: Code Generation (gen.sh)"
if bash gen.sh; then
  ok "DbSchema.java generated from schema.sql"
else
  fail "Code generation failed"
fi

# ---- Phase 2: Hot-Path Tests ----
step "Phase 2: Hot-Path Tests (fast feedback)"
if "${MVN[@]}" test -Dtest.groups=hot-path -q 2>&1; then
  ok "All hot-path tests passed"
else
  fail "Hot-path tests FAILED — fix before proceeding"
fi

# ---- Phase 3: All Tests ----
step "Phase 3: Full Test Suite"
if "${MVN[@]}" test -q 2>&1; then
  ok "All tests passed"
else
  fail "Some tests FAILED"
fi

# ---- Phase 4: Documentation Generation ----
step "Phase 4: Documentation Generation (gendocs.sh)"
if bash gendocs.sh; then
  ok "Documentation generated in docs/generated/"
else
  fail "Documentation generation failed"
fi

# ---- Phase 5: Package ----
step "Phase 5: Package (WAR)"
if "${MVN[@]}" package -DskipTests -q 2>&1; then
  WAR=$(ls -t target/*.war 2>/dev/null | head -1)
  ok "Packaged: ${WAR:-target/*.war}"
else
  fail "Package failed"
fi

echo ""
echo -e "${GREEN}${BOLD}✓ BUILD COMPLETE${NC}"
echo -e "  ${DIM}Hot-path tests:${NC}   src/test/java/.../payment/HotPathTest.java"
echo -e "  ${DIM}Generated code:${NC}   src/main/java/dev/nivic/db/DbSchema.java"
echo -e "  ${DIM}Generated docs:${NC}   docs/generated/"
echo -e "  ${DIM}Quick test:${NC}       ${MVN[0]} test -Dtest.groups=hot-path"
