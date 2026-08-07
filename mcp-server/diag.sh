#!/bin/bash
# BrowserDiag 快捷命令：./diag.sh <tool> '<json>'
# 或 ./diag.sh go-search  一键进入搜索页（含重试）
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "用法: ./diag.sh <tool> '<json>' | ./diag.sh go-search" >&2
  exit 2
fi

API="${BROWSERDIAG_API:-http://127.0.0.1:8788/api}"
TOKEN="${BROWSERDIAG_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  echo "请先设置 BROWSERDIAG_TOKEN（HTTP 服务要求 Token 认证）" >&2
  exit 2
fi

call_api() {
  curl -fsS -X POST "$API/$1" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    --data "${2:-{}}"
}

jqget() { python3 -c "import json,sys;print(json.load(sys.stdin)$1)"; }

case "$1" in
  go-search)
    call_api browser_open '{"url":"https://qq5855144.github.io/Musify-zh/","waitMs":15000}' >/dev/null
    # 1) 启用语义树（重试3次）
    for i in 1 2 3; do
      R=$(call_api browser_eval '{"expression":"(()=>{const el=document.querySelector(\"flt-semantics-placeholder\");if(el){el.click();return \"sem-clicked\"}return \"no-placeholder\"})()"}' | jqget "['result']")
      echo "sem($i): $R"
      [ "$R" = "sem-clicked" ] && break
      sleep 2
    done
    sleep 3
    # 2) 关闭更新检查弹窗（重试3次）
    for i in 1 2 3; do
      R=$(call_api browser_eval '{"expression":"(()=>{const els=[...document.querySelectorAll(\"flt-semantics\")];const btn=els.find(e=>e.textContent.trim()===\"否\");if(btn){btn.click();return \"closed\"}return \"no-dialog\"})()"}' | jqget "['result']")
      echo "dialog($i): $R"
      [ "$R" = "closed" ] && break
      sleep 2
    done
    sleep 2
    # 3) 点击左侧 rail 搜索 tab
    call_api browser_click_at '{"x":40,"y":100}' >/dev/null
    sleep 3
    echo "hash: $(call_api browser_eval '{"expression":"location.hash"}' | jqget "['result']")"
    ;;
  *)
    call_api "browser_$1" "${2:-{}}"
    echo ""
    ;;
esac
