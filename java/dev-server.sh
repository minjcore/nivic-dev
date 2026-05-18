#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if command -v mvn >/dev/null 2>&1; then
  MVN=(mvn)
else
  MVN=("${HOME}/.local/opt/apache-maven-3.9.9/bin/mvn")
fi

exec "${MVN[@]}" -q clean package cargo:run
