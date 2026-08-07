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

## 独立 APK 浏览器功能（v3.0 — Chrome 设计语言 + 矢量图标）

| 功能 | 说明 |
|---|---|
| **Chrome 风格 UI** | 浅色 Material 配色（#F0F0F0/#202020/#1A73E8），胶囊地址栏，全矢量图标（34 个 Material VectorDrawable，无 emoji） |
| **多标签页** | 最多 5 个标签，独立前进/后退栈，标签管理对话框 |
| **底部导航栏** | 主页 / 后退 / 前进 / 刷新 / 标签 / 菜单（全矢量图标） |
| **分组功能菜单** | 📌页面 / 🌐工具 / ⚙️设置 三组 18 项，图标+文字 |
| **书签** | 收藏当前页 / 管理（最多 100 条，持久化） |
| **保存页面** | outerHTML → 「下载」目录 .html |
| **分享页面** | 系统分享（文字+链接） |
| **页面查找** | 查找栏 + findAllAsync + 上一个/下一个 |
| **翻译本页** | Google 翻译中转 |
| **添加到桌面** | 主屏快捷方式（ShortcutManager） |
| **嗅探媒体** | JS 扫描 video/audio/扩展名 → 打开/下载/复制链接 |
| **页面资源** | 列出页面 img/script/link 资源 → 打开/下载 |
| **源码 zip** | HTML + meta + console/network 日志 + 静态资源 → zip |
| **生成二维码** | ZXing 生成当前页二维码（512px），可复制链接 |
| **语音播报** | TTS 朗读正文，再点停止 |
| **开发者工具** | API 地址/工具列表/console 统计 |
| **深色主题** | 一键切换并持久化，全局换肤 |
| **搜索引擎切换** | Google / Bing / 百度 / 搜狗 / DuckDuckGo |
| **UA 切换** | Android / 桌面 Chrome / iPhone Safari |
| **油猴脚本** | document-start 注入 + URL 匹配规则 |
| **历史记录** | 最近 50 条，一键清空 |
| **诊断 API** | 内嵌 :8788 HTTP 服务器（browser_source 等 10 个 API） |

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
