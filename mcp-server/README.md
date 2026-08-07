# BrowserDiag — 浏览器诊断 MCP 工具插件

调试 Web 应用的浏览器自动化工具集：收集 console 错误日志、网络失败请求、
截图、执行 JS、点击/输入/滚动操作、下载资源验证部署。

## 适用场景

- 手机端用户反馈"无法播放/页面异常"，无法查看 DevTools 时，在服务端复现诊断
- 验证 Web 应用部署（字节数对比、资源加载、console 报错）
- Flutter Web / 普通 Web 应用的自动化功能验证（搜索、播放、导航）

## 工具列表

| 工具 | 说明 |
|---|---|
| `browser_open` | 打开网页并采集日志（`mobile=true` 模拟手机 UA/视口） |
| `browser_state` | 页面状态摘要：URL/标题/console 统计/网络失败数/页面 iframe |
| `browser_console` | 查看 console 日志（`type` 过滤 error/warning/log，`limit` 条数） |
| `browser_network` | 查看网络请求（`onlyFailed=true` 只看失败/4xx/5xx） |
| `browser_eval` | 执行任意 JS 表达式并返回结果 |
| `browser_click` | 点击元素（CSS selector 或页面文本 text） |
| `browser_click_at` | 按坐标点击（Flutter canvas 场景必需） |
| `browser_keyboard` | 键盘输入文本 / 按键（Enter/Backspace 等） |
| `browser_wheel` | 鼠标滚轮滚动（Flutter 页面滚动） |
| `browser_wait` | 等待元素出现或等待毫秒数 |
| `browser_screenshot` | 截图保存（`fullPage` 整页） |
| `browser_save` | 下载 URL 内容到文件（验证部署产物字节数） |
| `browser_close` | 关闭浏览器清空日志 |

## 运行模式

### 1. HTTP 常驻模式（推荐，供终端/脚本调用）
```bash
cd /opt/browser-mcp
node server.js --http 8787 &
curl -s -X POST http://localhost:8787/api/browser_open -d '{"url":"https://example.com/"}'
curl -s -X POST http://localhost:8787/api/browser_console -d '{"limit":50}'
```

### 2. CLI 模式（一次性调用）
```bash
node server.js --cli browser_open '{"url":"https://example.com/"}'
```

### 3. MCP stdio 模式（平台注册）
```bash
node server.js
```

## 环境依赖

- Node.js >= 18
- Playwright Chromium（含 SwiftShader 软件渲染，arm64 可用）：
```bash
npm i playwright-core @modelcontextprotocol/sdk
PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright/ npx playwright-core install chromium
npx playwright-core install-deps chromium
```

## 关键实现细节

- **locale 必须设为 `zh-CN`**：无头 Chromium 默认 locale 异常会导致
  Flutter 应用初始化失败（`Incorrect locale information provided`）
- **必须用完整版 Chromium 而非 headless_shell**：headless_shell 无 GPU，
  Flutter Web 渲染报大量 shader 错误（SwiftShader 软件渲染 WebGL）
- `--autoplay-policy=no-user-gesture-required`：放开自动播放限制，
  便于验证播放器逻辑（移动端真实策略需在真机验证）
- Flutter Web 交互：先点击 `flt-semantics-placeholder` 启用语义树，
  然后用 `flt-semantics` 节点的 `getBoundingClientRect` 定位元素坐标点击

## 快速脚本

`diag.sh go-search`：一键打开 Musify 应用 → 启用语义树 → 关闭更新弹窗 →
进入搜索页（带重试）。
`diag.sh <tool> '<json>'`：调用任意工具。

## 验证记录（Musify-zh v5.6）

- 首页加载：0 console error，推荐播放列表渲染正常
- 搜索"美人鱼"：youtubei/results API 200，结果渲染（艺术家/专辑/播放列表）
- 播放器：点击歌曲后时间推进（音频播放中）
- 注意：无头环境（数据中心 IP）YouTube 返回英文内容，中文歌曲不出现，
  属环境差异非应用 bug
