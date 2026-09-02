#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAVA_DIR="${SKILL_DIR}/java"
MAIN_CLASS="com.xb.sgc.papererase.Main"
DEFAULT_JDK="/Library/Java/JavaVirtualMachines/jdk1.8.0_291.jdk/Contents/Home"

# 公司账号的 Ark 接入点与密钥由 ~/.zshrc 统一维护。用 zsh 子进程读取，避免 bash 解释
# zsh 专用语法；命令替换不向终端输出密钥。已有环境变量优先，不会被空值覆盖。
if command -v zsh >/dev/null 2>&1; then
  if [[ -z "${MST_XB_AI_ARK_MODEL_ENDPOINT:-}" ]]; then
    MST_XB_AI_ARK_MODEL_ENDPOINT="$(zsh -c 'source "$HOME/.zshrc" >/dev/null 2>&1; print -rn -- "${MST_XB_AI_ARK_MODEL_ENDPOINT:-}"')"
    export MST_XB_AI_ARK_MODEL_ENDPOINT
  fi
  if [[ -z "${MST_XB_AI_ARK_API_KEY:-}" ]]; then
    MST_XB_AI_ARK_API_KEY="$(zsh -c 'source "$HOME/.zshrc" >/dev/null 2>&1; print -rn -- "${MST_XB_AI_ARK_API_KEY:-}"')"
    export MST_XB_AI_ARK_API_KEY
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="${DEFAULT_JDK}"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ "${1:-}" == "echo" ]]; then
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"run <test-root> [--with-qrcode true|false] [--qrcode-width-cm 4.0-5.6]\""
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"gate <bad-root> <full-root> [--with-qrcode true|false] [--qrcode-width-cm 4.0-5.6]\""
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"resume <test-root> <run-dir> [--with-qrcode true|false] [--qrcode-width-cm 4.0-5.6]\""
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"report <run-dir>\""
  echo "mvn -q -DskipTests exec:java -Dexec.mainClass=${MAIN_CLASS} -Dexec.args=\"compare <run-dir-a> <run-dir-b> [run-dir-c]\""
  exit 0
fi

cd "${JAVA_DIR}"
mvn -q -DskipTests exec:java -Dexec.mainClass="${MAIN_CLASS}" -Dexec.args="$*"
