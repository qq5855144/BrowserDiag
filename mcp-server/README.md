# BrowserDiag MCP Server v3.2

基于 Playwright 的浏览器诊断/自动化服务，支持 MCP stdio、一次性 CLI 与带 Token 认证的 HTTP 常驻模式。

## 安装

```bash
npm ci
npx playwright-core install chromium
```

Node.js 要求 >= 18。Chromium 可执行文件会按以下顺序发现：

1. `BROWSERDIAG_CHROME` 或 `CHROME_PATH`
2. 系统 Chrome/Chromium
3. Playwright 浏览器缓存
4. Playwright 自身默认 executable resolution

## 运行

MCP stdio：

```bash
node server.js
```

CLI：

```bash
node server.js --cli browser_open '{"url":"https://example.com/"}'
```

HTTP（默认仅本机）：

```bash
export BROWSERDIAG_TOKEN='replace-with-a-random-token-at-least-16-chars'
node server.js --http 8788

curl -s http://127.0.0.1:8788/api/browser_state \
  -H "Authorization: Bearer $BROWSERDIAG_TOKEN"
```

如果没有设置 `BROWSERDIAG_TOKEN`，服务会为本次进程自动生成随机 Token 并输出到 stderr。

需要局域网监听时显式使用：

```bash
node server.js --http --port 8788 --host 0.0.0.0
```

浏览器跨域客户端默认被禁用；确有需要时设置单一允许源，例如：

```bash
export BROWSERDIAG_CORS_ORIGIN='https://your-tool.example'
```

## 工具

| 类别 | 工具 |
|---|---|
| 控制 | `browser_open` `browser_state` `browser_close` `browser_nav` `browser_resize` |
| 交互 | `browser_click` `browser_click_at` `browser_type` `browser_keyboard` `browser_wheel` `browser_wait` |
| 采集 | `browser_console` `browser_network` `browser_dom` `browser_links` `browser_screenshot` `browser_save` `browser_source` |
| 诊断 | `browser_perf` `browser_assert` `browser_report` `browser_run` `browser_route` |
| Flutter | `browser_flutter` |
| 内容/请求 | `browser_text` `browser_interactive` `browser_http` |

共 28 个工具。

## HTTP 安全边界

- 所有 GET/POST API 都要求 `Authorization: Bearer <token>` 或 `X-BrowserDiag-Token`。
- 默认 host 为 `127.0.0.1`。
- HTTP 请求体最大 256 KiB。
- `browser_open` / `browser_save` / `browser_http` 仅接受 HTTP(S) URL。
- `browser_text`、`browser_interactive`、viewport、timeout 等输入均有上限。

这些限制不会削弱 MCP stdio 的本地诊断能力。

## 测试

```bash
npm test
npm run check
npm audit --omit=dev --audit-level=high
```

测试不要求安装 Chromium，覆盖 URL 校验、HTTP 参数、Token 认证、viewport 修复、源码截断以及 HTTP 未授权拒绝。

## 快捷脚本

`diag.sh` 使用同一 HTTP Token：

```bash
export BROWSERDIAG_TOKEN='the-same-token-used-by-server'
./diag.sh state '{}'
```

`BROWSERDIAG_API` 可覆盖默认的 `http://127.0.0.1:8788/api`。
