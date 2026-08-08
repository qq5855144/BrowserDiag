# BrowserDiag v3.3

BrowserDiag 是面向 AI 与开发者的 Web 浏览器诊断工具，包含两个运行形态：

1. **Node MCP 服务**（`mcp-server/`）：提供 MCP stdio、CLI 和带 Token 认证的 HTTP 模式，基于 Playwright/Chromium。
2. **Android APK**（`android/`）：独立 WebView 浏览器，内置供 MCP / AI 工具调用的 Token HTTP Bridge（界面称「MCP 接口」），可按需开启局域网访问。

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

APK 提供多标签页、书签、历史、保存页面、分享、页面查找、翻译、二维码、TTS、媒体嗅探、资源查看、源码 ZIP、网络日志、下载管理、广告拦截、字体缩放、屏幕方向、UA/搜索引擎切换、用户脚本、深色主题、全屏、WebView 调试和可定制菜单。

Android UI 采用 Chrome / Material 3 风格重新设计：顶部 Omnibox 集成 HTTPS 状态、加载进度与刷新/停止；底栏固定为后退、前进、主页、标签、工具 5 个全局导航入口。工具中心按「当前页面快捷操作 / 常用工具 / 页面与内容 / 浏览数据 / 诊断与开发 / 浏览设置 / BrowserDiag」组织，并提供不受快捷配置影响的全部工具入口，避免功能被隐藏后失去访问路径。标签页、书签、历史、下载、Network、Console 与诊断工具统一使用可滚动的卡片式底部面板，并为深浅色、可访问性描述与功能语义整理图标。

用户脚本支持在网页中直接识别标准 `.user.js` 安装链接，也可粘贴脚本 URL 或手动创建；安装前展示名称、版本、来源、匹配/排除范围与兼容性提醒。媒体嗅探会合并 DOM 媒体、Performance 与 XHR/fetch 请求，识别视频、音频、HLS/DASH 清单并折叠媒体分片，同时提供格式、来源、大小/状态信息以及预览、下载和复制操作。

Android「MCP 接口」当前通过 Token 认证的 HTTP Bridge 暴露 17 个调用路由；它是给 MCP / AI 客户端使用的桥接接口，并非原生 MCP transport：

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

首次启动时 MCP 接口仅绑定本机。打开「工具 → 开发者工具」可复制实际 HTTP Bridge 地址和 MCP Token。需要电脑通过同一局域网访问时，再打开「工具 → 局域网 MCP 接口」。

请求示例：

```bash
curl -s -X POST http://PHONE_IP:8788/api/browser_open \
  -H "Authorization: Bearer YOUR_MCP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/"}'
```

## 安全说明

- Android MCP 接口含任意 JS、页面源码和自定义 HTTP 请求等高权限能力，因此其 HTTP Bridge 强制 Token 认证。
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
