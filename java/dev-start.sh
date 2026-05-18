#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

MVN=(mvn)
if ! command -v mvn >/dev/null 2>&1; then
  MVN=("${HOME}/.local/opt/apache-maven-3.9.9/bin/mvn")
fi

echo "Building..."
"${MVN[@]}" -q compile -DskipTests 2>&1 | grep -v "^WARNING:"

echo ""
echo "Starting Nivic Dev Server (in-memory, no PostgreSQL)..."
echo "  POST http://localhost:8080/sevlet/wallet/payload"
echo ""

exec "${MVN[@]}" exec:java \
  -Dexec.mainClass="dev.nivic.cli.DevServer" \
  -Dexec.classpathScope=compile \
  -Dexec.includeProjectDependenciesWithProvidedScope=true 2>&1 | grep -v "^WARNING:"