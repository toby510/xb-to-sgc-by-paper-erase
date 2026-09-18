#!/usr/bin/env bash
# 校验 config 生效的提示词与 references/提示词版本索引.md 的「当前生效版本」表是否一致。
# 用途：防止"改了 config 忘了登记""索引里换了当前版但 config 没跟着改"这类漂移。
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$SKILL_DIR/config/vlm-providers.json"
INDEX="$SKILL_DIR/references/提示词版本索引.md"

[[ -f "$CONFIG" ]] || { echo "找不到配置文件: $CONFIG" >&2; exit 2; }
[[ -f "$INDEX" ]] || { echo "找不到版本索引: $INDEX" >&2; exit 2; }

# config 里注册的提示词（locate / relocate / audit）
configured="$(grep -o '"prompt"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONFIG" \
  | sed 's/.*"\([^"]*\)"$/\1/' | xargs -n1 basename | sort)"

# 索引里「当前生效版本」表列出的文件（含协议契约）
declared="$(awk '/^## 当前生效版本/{flag=1; next} /^## /{flag=0} flag' "$INDEX" \
  | grep -o '`[^`]*\.md`' | tr -d '`' | xargs -n1 basename | sort)"

if [[ -z "$configured" ]]; then
  echo "config 里没有解析到任何提示词路径，请检查格式。" >&2
  exit 2
fi

missing="$(comm -23 <(echo "$configured") <(echo "$declared"))"
extra="$(comm -13 <(echo "$configured") <(echo "$declared") | grep -v 'VLM-CONTRACT' || true)"

status=0
if [[ -n "$missing" ]]; then
  echo "[未登记] config 已生效但索引「当前生效版本」表没有列：" >&2
  echo "$missing" | sed 's/^/   - /' >&2
  status=1
fi
if [[ -n "$extra" ]]; then
  echo "[多余/过期] 索引「当前生效版本」表列了 config 未生效的提示词：" >&2
  echo "$extra" | sed 's/^/   - /' >&2
  status=1
fi

if [[ "$status" -eq 0 ]]; then
  echo "[OK] 提示词版本索引与 config 一致："
  echo "$configured" | sed 's/^/   - /'
else
  echo "存在不一致，请更新 references/提示词版本索引.md 或 config/vlm-providers.json。" >&2
fi
exit "$status"
