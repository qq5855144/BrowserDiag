# BrowserDiag — 浏览器诊断工具（MCP 插件 + 独立 APK）

面向 AI 的 Web 应用诊断工具，**两种形态，同一套诊断能力**：

1. **MCP 插件**（`mcp-server/`）：Node.js 实现，可注册到任意支持 MCP 的 AI 平台，通过 stdio/HTTP 提供浏览器自动化诊断。
2. **独立 Android APK**（`android/`）：WebView 浏览器 + 内嵌 HTTP 诊断服务器，**供不支持安装插件的 AI 工具直接通过 HTTP 调用**（手机与电脑同一局域网即可）。

## 核心能力（24 个诊断工具 + 浏览器功能）

| 类别 | 工具 |
|---|---|
| 浏览器控制 | `browser_open` `browser_state` `browser_close` `browser_nav` `browser_resize` |
| 交互操作 | `browser_click` `browser_click_at` `browser_type` `browser_keyboard` `browser_wheel` |
| 信息采集 | `browser_console` `browser_network` `browser_dom` `browser_links` `browser_screenshot` `browser_save` |
| 代码执行 | `browser_eval` `browser_run`（脚本化多步流程） |
| 高级诊断 | `browser_perf`（TTFB/加载时序/慢资源） `browser_assert`（断言检查） `browser_report`（一键诊断报告） `browser_route`（请求拦截 mock） `browser_source`（页面源码） |
| Flutter 辅助 | `browser_flutter`（语义树启用/节点查询/按 label 点击） |

## 独立 APK 浏览器功能（v2.1）

| 功能 | 说明 |
|---|---|
| **底部导航栏** | 🏠主页 / ◀后退 / ▶前进 / ⟳刷新 / ☰菜单 |
| **地址/搜索合一** | 输入 URL 直接访问，输入关键词走当前搜索引擎 |
| **搜索引擎切换** | Google / Bing / 百度 / 搜狗 / DuckDuckGo（持久化） |
| **UA 切换** | Android 默认 / 桌面 Chrome / iPhone Safari（持久化） |
| **油猴脚本** | document-start 级注入，支持 URL 匹配规则、启用/停用、添加脚本 |
| **网页源码打包** | 当前页 HTML + 页面信息 + console/network 日志 + 静态资源 → zip 保存到「下载」 |
| **历史记录** | 最近 50 条访问记录，一键清空 |
| **文件下载** | 系统 DownloadManager 接管下载 |
| **缩放浏览** | 双指缩放 / 双击缩放 |
| **诊断 API** | 内嵌 :8788 HTTP 服务器，供 AI 工具调用（状态栏显示地址） |

## 快速开始

### MCP 插件（Node）

```bash
cd mcp-server
npm install
# stdio 模式（MCP 注册）
node server.js
# HTTP 常驻模式（供远程调用）
node server.js --http --port 8788
# CLI 模式
node server.js --cli browser_state
```

### 独立 APK（Android）

- 源码位于 `android/`，构建：
  ```bash
  cd android && ./gradlew assembleRelease
  ```
- 安装后启动 App，状态栏显示 API 地址 `http://<手机IP>:8788`
- 同一局域网内 AI 工具可直接调用：`http://<手机IP>:8788/api/browser_open` 等

## HTTP API（APK / Node 通用）

| 端点 | 说明 |
|---|---|
| `POST /api/browser_open` | 打开 URL（`{"url":"https://..."}`） |
| `GET /api/browser_state` | 当前页面状态（URL/标题/加载状态） |
| `GET /api/browser_console` | console 日志（含错误） |
| `GET /api/browser_network` | 网络请求记录（XHR/fetch） |
| `POST /api/browser_eval` | 执行 JS（`{"script":"..."}`） |
| `GET /api/browser_screenshot` | 截图（base64） |
| `GET /api/browser_perf` | 性能指标 |
| `GET /api/browser_report` | 一键诊断报告 |
| `GET /api/browser_close` | 关闭/重置 |

## 典型用法（配合 AI 排错）

1. `browser_open` 打开目标 Web 应用
2. `browser_console` + `browser_network` 采集错误与失败请求
3. `browser_eval` 注入代码验证修复
4. `browser_report` 一键生成诊断结论

## CI

`.github/workflows/build-apk.yml`：每次 push 自动构建 APK 并上传 artifact。

## 许可

MIT
