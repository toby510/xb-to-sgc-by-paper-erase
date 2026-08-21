#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAVA_DIR="${SKILL_DIR}/java"
MAIN_CLASS="com.xb.sgc.papererase.Main"

if [[ "${1:-}" == "echo" ]]; then
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"run <test-root>\""
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"gate <bad-root> <full-root>\""
  exit 0
fi

cd "${JAVA_DIR}"
mvn -q -DskipTests exec:java -Dexec.mainClass="${MAIN_CLASS}" -Dexec.args="$*"
