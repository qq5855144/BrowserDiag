---
name: BrowserDiag
description: 基于 Playwright 的浏览器诊断与自动化 MCP 技能包（28 个工具），支持 MCP stdio / CLI / HTTP 三种模式，覆盖浏览器控制、页面交互、DOM/网络采集、性能诊断、Flutter 测试等场景。安装后 Operit 可通过本地 MCP 插件直接调用浏览器能力。
---

# BrowserDiag 技能包

基于 Playwright 的浏览器诊断与自动化 MCP 服务器（BrowserDiag MCP Server），本技能包让 Operit 通过仓库链接一键安装并使用浏览器能力：打开/关闭浏览器、点击输入、抓取 DOM 与链接、采集控制台与网络请求、截图保存、性能断言、自定义路由拦截、Flutter 控件测试等，共 28 个工具。

## 能力概述

| 类别 | 能力 |
|---|---|
| 控制 | `browser_open` `browser_state` `browser_close` `browser_nav` `browser_resize` |
| 交互 | `browser_click` `browser_click_at` `browser_type` `browser_keyboard` `browser_wheel` `browser_wait` |
| 采集 | `browser_console` `browser_network` `browser_dom` `browser_links` `browser_screenshot` `browser_save` `browser_source` |
| 诊断 | `browser_perf` `browser_assert` `browser_report` `browser_run` `browser_route` |
| Flutter | `browser_flutter` |
| 内容/请求 | `browser_text` `browser_interactive` `browser_http` |

运行模式：MCP stdio（`node server.js`）、一次性 CLI（`node server.js --cli ...`）、带 Token 认证的 HTTP 常驻（`node server.js --http 8788`）。

## 在 Operit 中安装（通过仓库链接）

1. 获取本仓库：`https://github.com/qq5855144/BrowserDiag`（下载 zip 或 git clone 均可）。
2. 安装 MCP 插件：
   - 将仓库中的 `mcp-server/` 目录（含 `package.json`、`server.js`、`README.md` 等标志文件）复制到 Android 侧 `/sdcard/Download/Operit/mcp_plugins/browserdiag/`。
   - 将 `mcp-server/mcp.config.json` 中 `mcpServers.browserdiag` 条目合并进 `/sdcard/Download/Operit/mcp_plugins/mcp_config.json`。
   - 系统会在 Linux 侧 `~/mcp_plugins/browserdiag/` 部署并自动执行依赖安装（`npm install` / `npm ci`），随后以 `node server.js` 启动。
   - 若使用远程/命令型方式，也可配置 `"command": "npx", "args": ["-y", "browser-diag"]`（需先发布到 npm，或使用本地 node 方式）。
3. 安装技能（可选，本文件即技能本体）：
   - 将 `.skills/browserdiag/` 目录复制到 `/sdcard/Download/Operit/skills/browserdiag/`，确保该目录内含本 `SKILL.md`。
4. 验证：在 Operit 中 `use_package` / `ping_mcp` 探测 `browserdiag`，确认工具可拉取后可调用 `browser_open` 等工具。

## 使用示例

打开网页并截图：

```
browser_open {"url": "https://example.com"}
browser_screenshot {}
```

抓取页面链接与 DOM：

```
browser_links {"max": 20}
browser_dom {"selector": "h1"}
```

网络与性能诊断：

```
browser_network {}
browser_perf {"type": "navigation"}
```

安全提示：HTTP 模式必须设置 `BROWSERDIAG_TOKEN`（至少 16 位随机字符），默认仅监听 127.0.0.1；不要把 Token 提交到仓库。

## 测试与质量

```bash
cd mcp-server
npm ci
npm run check
npm audit --omit=dev --audit-level=high
```
