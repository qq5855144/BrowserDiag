#!/bin/bash
# BrowserDiag 快捷命令：./diag.sh <tool> '<json>'
# 或 ./diag.sh go-search  一键进入搜索页（含重试）
API=http://localhost:8787/api
jqget() { python3 -c "import json,sys;print(json.load(sys.stdin)$1)"; }

case "$1" in
  go-search)
    curl -s -X POST $API/browser_open -d '{"url":"https://qq5855144.github.io/Musify-zh/","waitMs":15000}' >/dev/null
    # 1) 启用语义树（重试3次）
    for i in 1 2 3; do
      R=$(curl -s -X POST $API/browser_eval -d '{"expression":"(()=>{const el=document.querySelector(\"flt-semantics-placeholder\");if(el){el.click();return \"sem-clicked\"}return \"no-placeholder\"})()"}' | jqget "['result']")
      echo "sem($i): $R"
      [ "$R" = "sem-clicked" ] && break
      sleep 2
    done
    sleep 3
    # 2) 关闭更新检查弹窗（重试3次）
    for i in 1 2 3; do
      R=$(curl -s -X POST $API/browser_eval -d '{"expression":"(()=>{const els=[...document.querySelectorAll(\"flt-semantics\")];const btn=els.find(e=>e.textContent.trim()===\"否\");if(btn){btn.click();return \"closed\"}return \"no-dialog\"})()"}' | jqget "['result']")
      echo "dialog($i): $R"
      [ "$R" = "closed" ] && break
      sleep 2
    done
    sleep 2
    # 3) 点击左侧 rail 搜索 tab
    curl -s -X POST $API/browser_click_at -d '{"x":40,"y":100}' >/dev/null
    sleep 3
    echo "hash: $(curl -s -X POST $API/browser_eval -d '{"expression":"location.hash"}' | jqget "['result']")"
    ;;
  *)
    curl -s -X POST $API/browser_$1 -d "$2"
    echo ""
    ;;
esac