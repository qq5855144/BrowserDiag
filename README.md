# BrowserDiag v3.5

BrowserDiag 是面向 AI 与开发者的 Web 浏览器诊断工具，包含两个运行形态：

1. **Node MCP 服务**（`mcp-server/`）：提供 MCP stdio、CLI 和带 Token 认证的 HTTP 模式，基于 Playwright/Chromium。
2. **Android APK**（`android/`）：独立 WebView 浏览器，内置原生 MCP Streamable HTTP 服务，并保留 Token HTTP Bridge 兼容路由，可按需开启局域网访问。

## v3.5 原生 MCP Streamable HTTP

- Android 端现在提供真正的 MCP JSON-RPC endpoint：标准地址为 `/mcp`，根地址 `/` 也可直接连接，因此 `http://PHONE_IP:8788` 和 `http://PHONE_IP:8788/mcp` 都可用于 MCP 客户端。
- 同时支持当前 MCP `2026-07-28` 的无状态 `server/discover` / `tools/list` / `tools/call`，并兼容 `2025-11-25`、`2025-06-18`、`2025-03-26` 客户端的 `initialize` / `initialized` 握手。
- 18 个 Android 浏览器工具现在直接通过 MCP `tools/list` 发现、通过 `tools/call` 调用；原有 `/api/browser_*` HTTP Bridge 继续保留给旧集成使用。
- 远程 MCP 默认要求 `Authorization: Bearer <MCP Token>`（也兼容 `X-BrowserDiag-Token`）；开发者工具中可以复制实际 MCP URL 和 Token。
- 对只能填写一个 MCP URL、无法配置 Header 的客户端，新增显式的「MCP URL-only 兼容」开关。它只在局域网监听已开启时可用，关闭局域网监听会自动关闭该模式。
- MCP HTTP 层增加请求大小限制、Origin 校验、无状态响应与 CORS 预检约束；URL-only 模式会开放高权限浏览器控制，只应在可信开发/家庭网络临时使用。

## v3.4 网络实验室

- 新增持久化网络规则引擎，支持优先级、URL/请求方法匹配、启停、复制、删除、命中记录与从 Network 请求一键创建规则。
- 请求侧支持阻止、URL 重写、Header 注入、fetch/XHR 文本 Body 替换和 1-10000 ms 延迟；URL 支持通配符、`regex:` 与正则捕获组。
- 响应侧支持 Mock 状态/Header/Body、响应 Header 重写及 HTML/JSON/JS/CSS/XML 等文本响应内容替换；原生代理有超时、大小与流式回退保护。
- JS/CSS 可按页面 URL 在 document-start 注入；CSS 启停/编辑可在当前页热更新，JS 规则在后续导航继续以 document-start 执行。
- fetch/XHR/WebSocket/EventSource Hook 会记录规则命中、状态、MIME、大小与耗时；Service Worker 请求也接入原生规则层，旧版 WebView 不支持 document-start 时自动降级为页面加载后 Hook。
- 新增 MCP 调用路由 `browser_network_rules`，可远程 `list` / `hits` / `add` / `update` / `enable` / `delete` / `clear_hits`，与 App 内规则列表使用同一数据源。
- 网络实验室不安装证书、不做 HTTPS MITM；页面/静态资源由 WebView 原生拦截层处理，fetch/XHR 的请求 Body 等 WebView 未暴露的信息由页面前置 Hook 处理。

## v3.3 浏览与诊断增强

- 标签容量由 5 提升至 8，底层不再在达到上限时静默销毁旧标签；支持关闭当前、单独关闭和确认关闭全部。
- 用户脚本支持网页 `.user.js` 识别、URL 安装、元数据/匹配范围预览、更新识别与运行时 include/exclude 校验。
- 媒体嗅探合并 DOM、Performance、XHR/fetch 信号，区分视频、音频、HLS/DASH 和分片，并提供格式、大小、状态与操作入口。
- 页面资源改为 DOM + Performance 双来源分类面板，自动去重并识别图片、脚本、样式、字体和媒体，不再只展示 URL。
- Network 面板展示方法、状态、请求类型、MIME、大小和耗时，异常请求醒目标记，并支持清空与复制诊断信息。
- 源码归档升级为有界递归 ZIP：从 HTML + Resource Timing 出发，继续抓取 CSS `@import/url()`、JS module、source map 源码和 iframe 依赖，并生成资源/失败清单。
- 普通下载、媒体下载和资源下载统一携带 Cookie、Referer 与 User-Agent；下载中心显示任务进度、大小和失败状态。
- 地址栏可正确识别 `example.com/path`、IP:port 与 localhost；历史支持单条删除，清空操作增加确认。

## v3.2 质量增强

- 修复 APK 启动时 `Tabs` 未初始化以及首次切换使用 `-1` 下标导致的确定性崩溃。
- 修复 Android `evaluateJavascript` 返回值二次 JSON 编码导致 state/network/perf/text 等接口解析失败。
- Android MCP 调用 HTTP Bridge 增加 Bearer Token 认证、请求体限制、响应上限和 URL 校验；默认仅监听 `127.0.0.1`。
- 新增 Android「局域网 MCP 接口」显式开关；端口 8788 被占用时会显示实际回退端口。
- 修复全屏模式没有可用退出入口、下载管理 `file://` 兼容、快捷方式 URL 启动、屏幕方向/网页调试设置重启失效。
- WebView 禁止网页直接访问本地 file/content URI，并启用 Safe Browsing（系统支持时）。
- 源码 ZIP 打包支持相对 CSS/JS URL，并限制单个资源大小，避免异常资源耗尽内存。
- MCP 去除固定 Chromium revision/path，支持 Playwright 默认安装、系统 Chrome/Chromium 和 `BROWSERDIAG_CHROME`。
- 修复 `browser_resize` 调错 Playwright 对象，并补齐 `browser_source`。
- Node HTTP 默认只监听本机、强制 Token、限制请求体；新增 6 个回归测试。
- GitHub Actions 同时检查 Node 与 Android：Node 测试/依赖审计，Android lint/单元测试任务/debug+release 构建。

## Node MCP 能力（28 个工具）

| 类别 | 工具 |
|---|---|
| 浏览器控制 | `browser_open` `browser_state` `browser_close` `browser_nav` `browser_resize` |
| 交互 | `browser_click` `browser_click_at` `browser_type` `browser_keyboard` `browser_wheel` `browser_wait` |
| 采集 | `browser_console` `browser_network` `browser_dom` `browser_links` `browser_screenshot` `browser_save` `browser_source` |
| 诊断 | `browser_perf` `browser_assert` `browser_report` `browser_run` `browser_route` |
| Flutter | `browser_flutter` |
| v3.1+ | `browser_text` `browser_interactive` `browser_http` |

## Android 浏览器

APK 提供多标签页、书签、历史、保存页面、分享、页面查找、翻译、二维码、TTS、媒体嗅探、资源查看、递归源码 ZIP、网络日志、网络实验室、下载管理、广告拦截、字体缩放、屏幕方向、UA/搜索引擎切换、用户脚本、深色主题、全屏、WebView 调试和可定制菜单。

Android UI 采用 Chrome / Material 3 风格重新设计：顶部 Omnibox 集成 HTTPS 状态、加载进度与刷新/停止；底栏固定为后退、前进、主页、标签、工具 5 个全局导航入口。工具中心按「当前页面快捷操作 / 常用工具 / 页面与内容 / 浏览数据 / 诊断与开发 / 浏览设置 / BrowserDiag」组织，并提供不受快捷配置影响的全部工具入口，避免功能被隐藏后失去访问路径。标签页、书签、历史、下载、Network、Console 与诊断工具统一使用可滚动的卡片式底部面板，并为深浅色、可访问性描述与功能语义整理图标。

用户脚本支持在网页中直接识别标准 `.user.js` 安装链接，也可粘贴脚本 URL 或手动创建；安装前展示名称、版本、来源、匹配/排除范围与兼容性提醒。媒体嗅探会合并 DOM 媒体、Performance 与 XHR/fetch 请求，识别视频、音频、HLS/DASH 清单并折叠媒体分片，同时提供格式、来源、大小/状态信息以及预览、下载和复制操作。

Android「MCP 接口」通过原生 Streamable HTTP 暴露以下 18 个工具；MCP endpoint 为 `/mcp`（根 `/` 是兼容别名），旧版 `/api/browser_*` HTTP Bridge 仍可直接调用同一实现：

| 调用路由 | 作用 |
|---|---|
| `browser_open` / `browser_close` / `browser_state` | 页面打开、重置与状态 |
| `browser_console` / `browser_network` / `browser_netlog` | Console 与网络诊断 |
| `browser_eval` / `browser_source` / `browser_text` | JS、源码和正文 |
| `browser_screenshot` | PNG 截图，返回 metadata + base64 |
| `browser_perf` / `browser_report` | 性能与综合报告 |
| `browser_tabs` / `browser_interactive` | 标签和可交互元素 |
| `browser_history` / `browser_bookmarks` | 历史与书签 |
| `browser_http` | 带响应大小限制的 HTTP GET |
| `browser_network_rules` | 网络实验室规则与命中记录管理 |

## 快速开始

### Node MCP

```bash
cd mcp-server
npm ci
npx playwright-core install chromium

# MCP stdio
node server.js

# CLI
node server.js --cli browser_state
```

BrowserDiag 会依次尝试 `BROWSERDIAG_CHROME` / `CHROME_PATH`、系统 Chrome/Chromium、Playwright 缓存以及 Playwright 默认可执行文件。

### Node HTTP

HTTP 模式默认监听 `127.0.0.1:8788`，必须设置或使用启动时自动生成的 Token：

```bash
export BROWSERDIAG_TOKEN='replace-with-a-random-token-at-least-16-chars'
node server.js --http 8788

curl -s http://127.0.0.1:8788/api/browser_state \
  -H "Authorization: Bearer $BROWSERDIAG_TOKEN"
```

如确实需要局域网访问，显式指定 `--host 0.0.0.0`。浏览器跨域客户端还需显式设置 `BROWSERDIAG_CORS_ORIGIN`；默认不开放 CORS。

### Android APK

```bash
cd android
./gradlew assembleRelease
```

首次启动时 MCP 接口仅绑定本机。需要电脑通过同一局域网访问时，先打开「工具 → BrowserDiag → 局域网 MCP 接口」，再到「工具 → 开发者工具」复制实际 MCP URL 和 MCP Token。

推荐配置（客户端支持自定义 Header）：

```text
URL: http://PHONE_IP:8788/mcp
Authorization: Bearer YOUR_MCP_TOKEN
```

根地址也可直接作为 MCP URL：`http://PHONE_IP:8788`。

如果 AI 客户端的 MCP 面板**只能填写 URL，不能设置 Authorization Header**，请在可信局域网中额外开启「工具 → BrowserDiag → MCP URL-only 兼容」，之后直接填写 `http://PHONE_IP:8788` 即可。该模式不需要 Token，使用完成后建议关闭。

原生 MCP `initialize` 烟测示例：

```bash
curl -s -X POST http://PHONE_IP:8788/mcp \
  -H "Authorization: Bearer YOUR_MCP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1"}}}'
```

旧 HTTP Bridge 请求示例：

```bash
curl -s -X POST http://PHONE_IP:8788/api/browser_open \
  -H "Authorization: Bearer YOUR_MCP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/"}'
```

## 安全说明

- Android MCP 接口含任意 JS、页面源码和自定义 HTTP 请求等高权限能力，因此默认要求随机 Token；旧 `/api/*` HTTP Bridge 始终要求 Token。
- 「MCP URL-only 兼容」仅用于无法设置认证 Header 的 MCP 客户端。开启后，任何能访问该 MCP 端口的设备都可能调用这些高权限能力；不要在公共 Wi-Fi、端口转发或不可信网络环境中启用。
- Android 默认关闭局域网监听，并关闭应用数据备份，避免历史、书签、用户脚本与 MCP Token 被系统备份。
- 不要把 `BROWSERDIAG_TOKEN` 写入仓库；`.env` 已加入忽略规则。
- WebView 远程调试默认关闭，只在用户显式开启时生效。

## 质量检查

```bash
cd mcp-server
npm run check
npm audit --omit=dev --audit-level=high
```

GitHub Actions 会在相关 push/PR 上运行 Node 检查，以及：

```bash
cd android
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

## 许可

[MIT](LICENSE)
