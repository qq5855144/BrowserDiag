#!/usr/bin/env node
/**
 * BrowserDiag MCP Server — 浏览器诊断工具插件
 *
 * 用途：调试 Web 应用（收集 console 错误日志 / 网络请求日志 / 截图 /
 *       执行 JS / 点击验证 / 下载资源对比），特别适合无法打开 DevTools
 *       的场景（如手机端用户反馈问题时的服务端复现诊断）。
 *
 * 双模式：
 *   1) MCP stdio 模式：默认启动，供 Operit 平台注册
 *   2) CLI 模式：node server.js --cli <tool> '<jsonParams>'
 *       例：node server.js --cli browser_open '{"url":"https://qq5855144.github.io/Musify-zh/"}'
 */
import { chromium } from 'playwright-core';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import os from 'node:os';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_LOG = 800;
const MAX_HTTP_BODY_BYTES = 256 * 1024;
const DEFAULT_HTTP_PORT = 8788;
const DEFAULT_HTTP_HOST = '127.0.0.1';

export function clampInt(value, fallback, min, max) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export function normalizeHttpUrl(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error('url is required');
  }
  let target;
  try {
    target = new URL(value.trim());
  } catch {
    throw new Error('invalid url');
  }
  if (target.protocol !== 'http:' && target.protocol !== 'https:') {
    throw new Error('only http(s) urls are allowed');
  }
  return target.href;
}

export function parseHttpOptions(args = [], env = process.env) {
  const parsePort = (value, fallback) => {
    if (value === undefined || value === null || value === '') return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed >= 1 && parsed <= 65535 ? parsed : NaN;
  };
  let host = env.BROWSERDIAG_HOST || DEFAULT_HTTP_HOST;
  let port = parsePort(env.BROWSERDIAG_PORT, DEFAULT_HTTP_PORT);
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === '--port') {
      port = parsePort(args[++i], NaN);
    } else if (arg === '--host') {
      host = String(args[++i] || '').trim();
    } else if (/^\d+$/.test(arg) && i === 0) {
      port = parsePort(arg, NaN);
    } else {
      throw new Error('unknown HTTP option: ' + arg);
    }
  }
  if (!Number.isFinite(port)) throw new Error('invalid HTTP port');
  if (!host) throw new Error('invalid HTTP host');
  return { host, port };
}

export function isAuthorized(headers, token) {
  if (!token) return false;
  const authorization = String(headers?.authorization || '');
  const bearer = /^Bearer\s+(.+)$/i.exec(authorization)?.[1]?.trim();
  const direct = String(headers?.['x-browserdiag-token'] || '').trim();
  const candidate = bearer || direct;
  if (!candidate) return false;
  const expectedBytes = Buffer.from(token);
  const candidateBytes = Buffer.from(candidate);
  return expectedBytes.length === candidateBytes.length &&
    crypto.timingSafeEqual(expectedBytes, candidateBytes);
}

function isExecutable(file) {
  if (!file) return false;
  try {
    fs.accessSync(file, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

export function resolveBrowserExecutable(env = process.env) {
  const explicit = env.BROWSERDIAG_CHROME || env.CHROME_PATH;
  if (explicit) {
    if (!isExecutable(explicit)) {
      throw new Error('configured Chromium executable is not accessible: ' + explicit);
    }
    return explicit;
  }

  const candidates = process.platform === 'win32'
    ? [
        path.join(env.PROGRAMFILES || '', 'Google/Chrome/Application/chrome.exe'),
        path.join(env['PROGRAMFILES(X86)'] || '', 'Google/Chrome/Application/chrome.exe'),
      ]
    : process.platform === 'darwin'
      ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
      : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  for (const candidate of candidates) {
    if (isExecutable(candidate)) return candidate;
  }

  const cacheRoot = env.PLAYWRIGHT_BROWSERS_PATH && env.PLAYWRIGHT_BROWSERS_PATH !== '0'
    ? env.PLAYWRIGHT_BROWSERS_PATH
    : path.join(os.homedir(), '.cache', 'ms-playwright');
  try {
    const dirs = fs.readdirSync(cacheRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && /^chromium-\d+$/.test(entry.name))
      .map((entry) => entry.name)
      .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
    for (const dir of dirs) {
      for (const suffix of ['chrome-linux/chrome', 'chrome-linux64/chrome']) {
        const candidate = path.join(cacheRoot, dir, suffix);
        if (isExecutable(candidate)) return candidate;
      }
    }
  } catch {
    // Playwright's default executable resolution gets the final chance below.
  }
  return null;
}

export class BrowserDiag {
  constructor() {
    this.browser = null;
    this.page = null;
    this.consoleLogs = [];   // {ts,type,text}
    this.networkLogs = [];   // {ts,url,method,status,failed,errorText,resourceType}
    this.pageErrors = [];
    this.context = null;
    this.mobileMode = null;
  }

  async _ensurePage({ mobile } = {}) {
    if (!this.browser) {
      const executablePath = resolveBrowserExecutable();
      const launchArgs = [
        '--disable-dev-shm-usage',
        '--autoplay-policy=no-user-gesture-required',
        '--lang=zh-CN',
        '--use-gl=angle',
        '--use-angle=swiftshader',
        '--enable-unsafe-swiftshader',
        '--disable-blink-features=AutomationControlled',
      ];
      if (typeof process.getuid === 'function' && process.getuid() === 0) {
        launchArgs.unshift('--no-sandbox');
      }
      const launchOptions = {
        headless: true,
        args: launchArgs,
        ...(executablePath ? { executablePath } : {}),
      };
      try {
        this.browser = await chromium.launch(launchOptions);
      } catch (error) {
        throw new Error(
          'Chromium launch failed. Run "npx playwright-core install chromium" or set BROWSERDIAG_CHROME. ' +
          String(error?.message || error)
        );
      }
    }
    const desiredMobile = mobile === undefined ? (this.mobileMode ?? false) : !!mobile;
    if (this.context && this.mobileMode !== desiredMobile) {
      await this.context.close();
      this.context = null;
      this.page = null;
    }
    if (!this.context) {
      const isMobile = desiredMobile;
      this.context = await this.browser.newContext({
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai',
        ...(isMobile
          ? {
              userAgent:
                'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
              viewport: { width: 390, height: 844 },
              isMobile: true,
              hasTouch: true,
              deviceScaleFactor: 3,
            }
          : {
              viewport: { width: 1280, height: 800 },
              userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36',
            }),
      });
      this.mobileMode = isMobile;
      this.page = await this.context.newPage();
      // ---- 日志采集 ----
      this.page.on('console', (msg) => {
        this.consoleLogs.push({ ts: Date.now(), type: msg.type(), text: msg.text() });
        if (this.consoleLogs.length > MAX_LOG) this.consoleLogs.shift();
      });
      this.page.on('pageerror', (err) => {
        this.pageErrors.push({ ts: Date.now(), text: String(err.message || err) });
        if (this.pageErrors.length > 200) this.pageErrors.shift();
      });
      this.page.on('requestfailed', (req) => {
        this.networkLogs.push({
          ts: Date.now(), url: req.url(), method: req.method(),
          status: 0, failed: true, errorText: req.failure()?.errorText || 'unknown',
          resourceType: req.resourceType(),
        });
        if (this.networkLogs.length > MAX_LOG) this.networkLogs.shift();
      });
      this.page.on('response', (res) => {
        if (res.status() >= 400 || res.request().resourceType() === 'document' || res.request().resourceType() === 'xhr' || res.request().resourceType() === 'fetch') {
          this.networkLogs.push({
            ts: Date.now(), url: res.url(), method: res.request().method(),
            status: res.status(), failed: false, errorText: '',
            resourceType: res.request().resourceType(),
          });
          if (this.networkLogs.length > MAX_LOG) this.networkLogs.shift();
        }
      });
    }
    return this.page;
  }

  async open({ url, mobile = false, waitMs = 8000 }) {
    const targetUrl = normalizeHttpUrl(url);
    const page = await this._ensurePage({ mobile });
    const settleMs = clampInt(waitMs, 8000, 0, 15000);
    // 导航前清空日志，保证当前页面日志独立
    this.consoleLogs = [];
    this.networkLogs = [];
    this.pageErrors = [];
    let navErr = null;
    try {
      await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
    } catch (e) {
      navErr = e.message.split('\n')[0];
    }
    if (settleMs > 0) await page.waitForTimeout(settleMs);
    const state = await page.evaluate(() => ({
      title: document.title,
      url: location.href,
      ready: document.readyState,
      bodyTextLen: (document.body?.innerText || '').length,
    })).catch(() => ({}));
    return { ok: !navErr, navErr, state };
  }

  async state() {
    const page = this.page;
    if (!page) return { error: 'browser not opened yet' };
    const s = await page.evaluate(() => ({
      title: document.title,
      url: location.href,
      ready: document.readyState,
      hasCanvas: !!document.querySelector('flt-glass-pane, flutter-view'),
      bodyText: (document.body?.innerText || '').slice(0, 2000),
      iframes: [...document.querySelectorAll('iframe')].map((f) => f.src),
    })).catch((e) => ({ evalErr: String(e) }));
    const consoleTypes = {};
    for (const l of this.consoleLogs) consoleTypes[l.type] = (consoleTypes[l.type] || 0) + 1;
    const netFail = this.networkLogs.filter((n) => n.failed || n.status >= 400);
    return {
      page: s,
      consoleCount: this.consoleLogs.length,
      consoleTypes,
      networkFailCount: netFail.length,
      pageErrorCount: this.pageErrors.length,
    };
  }

  async console({ type = '', limit = 50 } = {}) {
    limit = clampInt(limit, 50, 1, 500);
    let list = this.consoleLogs;
    if (type) list = list.filter((l) => l.type === type);
    const shown = list.slice(-limit).reverse();
    const errors = list.filter((l) => l.type === 'error');
    return { total: list.length, errorCount: errors.length, logs: shown };
  }

  async network({ onlyFailed = false, limit = 100 } = {}) {
    limit = clampInt(limit, 100, 1, 500);
    let list = this.networkLogs;
    if (onlyFailed) list = list.filter((n) => n.failed || n.status >= 400);
    const shown = list.slice(-limit).reverse();
    return { total: list.length, logs: shown };
  }

  async eval({ expression }) {
    const page = await this._ensurePage();
    const result = await page.evaluate(expression);
    return { result };
  }

  async click({ selector, text = '', timeout = 10000 }) {
    const page = await this._ensurePage();
    timeout = clampInt(timeout, 10000, 100, 60000);
    if (text) {
      await page.getByText(text, { exact: false }).first().click({ timeout });
      return { clicked: `text="${text}"` };
    }
    if (!selector) throw new Error('selector or text is required');
    await page.click(selector, { timeout });
    await page.waitForTimeout(800);
    return { clicked: selector };
  }

  async clickAt({ x, y }) {
    const page = await this._ensurePage();
    await page.mouse.click(x, y);
    await page.waitForTimeout(800);
    return { clickedAt: { x, y } };
  }

  async wheel({ deltaY = 300, deltaX = 0 }) {
    const page = await this._ensurePage();
    await page.mouse.wheel(deltaX, deltaY);
    await page.waitForTimeout(600);
    return { scrolled: { deltaX, deltaY } };
  }

  async keyboard({ text, key = '' }) {
    const page = await this._ensurePage();
    if (key) {
      await page.keyboard.press(key);
    } else {
      if (typeof text !== 'string') throw new Error('text or key is required');
      await page.keyboard.type(text, { delay: 60 });
    }
    return { typed: text || key };
  }

  async type({ selector, text, fill = true }) {
    const page = await this._ensurePage();
    if (fill) {
      await page.fill(selector, text);
    } else {
      await page.click(selector);
      await page.keyboard.type(text);
    }
    return { typed: selector };
  }

  async wait({ selector = '', timeout = 10000 }) {
    const page = await this._ensurePage();
    timeout = clampInt(timeout, 10000, 0, 60000);
    if (selector) {
      await page.waitForSelector(selector, { timeout });
      return { waited: `selector ${selector} appeared` };
    }
    await page.waitForTimeout(timeout);
    return { waited: `${timeout}ms` };
  }

  async screenshot({ path: p = path.join(process.cwd(), 'browser-diag-shots', 'shot.png'), fullPage = false } = {}) {
    const page = await this._ensurePage();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    await page.screenshot({ path: p, fullPage });
    const size = fs.statSync(p).size;
    return { path: p, bytes: size };
  }

  async save({ url, path: p, headers = '' } = {}) {
    const page = await this._ensurePage();
    const targetUrl = normalizeHttpUrl(url);
    if (!p || typeof p !== 'string') throw new Error('path is required');
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const h = headers ? JSON.parse(headers) : {};
    const res = await page.request.get(targetUrl, { headers: h, timeout: 60000 });
    const buf = await res.body();
    fs.writeFileSync(p, buf);
    return { url: targetUrl, path: p, bytes: buf.length, status: res.status() };
  }

  async source({ maxLen = 500000 } = {}) {
    const page = await this._ensurePage();
    const safeMaxLen = clampInt(maxLen, 500000, 1, 2000000);
    const html = await page.content();
    return {
      url: page.url(),
      htmlLength: html.length,
      truncated: html.length > safeMaxLen,
      html: html.slice(0, safeMaxLen),
    };
  }

  async perf() {
    const page = await this._ensurePage();
    const r = await page.evaluate(() => {
      const nav = performance.getEntriesByType('navigation')[0];
      const paint = performance.getEntriesByType('paint');
      const res = performance.getEntriesByType('resource');
      const byType = {};
      for (const e of res) { const t = e.initiatorType || 'other'; byType[t] = (byType[t] || 0) + 1; }
      const slow = res.filter(e => e.duration > 2000).sort((a, b) => b.duration - a.duration).slice(0, 10)
        .map(e => ({ name: e.name.slice(0, 120), dur: Math.round(e.duration), size: e.transferSize }));
      const total = res.reduce((s, e) => s + (e.transferSize || 0), 0);
      return {
        url: location.href,
        timing: nav ? {
          ttfb: Math.round(nav.responseStart - nav.requestStart),
          domContentLoaded: Math.round(nav.domContentLoadedEventEnd - nav.startTime),
          load: Math.round(nav.loadEventEnd - nav.startTime),
          transferSize: nav.transferSize,
          protocol: nav.nextHopProtocol,
        } : null,
        paint: Object.fromEntries(paint.map(p => [p.name, Math.round(p.startTime)])),
        resources: { count: res.length, totalBytes: total, byType },
        slowResources: slow,
        memory: performance.memory ? { usedMB: Math.round(performance.memory.usedJSHeapSize / 1048576), totalMB: Math.round(performance.memory.totalJSHeapSize / 1048576) } : null,
      };
    }).catch(e => ({ evalErr: String(e) }));
    return r;
  }

  async assert({ checks = [] } = {}) {
    const page = await this._ensurePage();
    const results = [];
    for (const c of checks) {
      try {
        if (c.type === 'url_contains') {
          results.push({ check: c.type, value: c.value, passed: page.url().includes(c.value), actual: page.url() });
        } else if (c.type === 'text_exists') {
          const body = await page.evaluate(() => document.body?.innerText || '');
          results.push({ check: c.type, value: c.value, passed: body.includes(c.value) });
        } else if (c.type === 'selector_exists') {
          const n = await page.locator(c.value).count();
          results.push({ check: c.type, value: c.value, passed: n > 0, actual: n });
        } else if (c.type === 'console_no_error') {
          const errs = this.consoleLogs.filter(l => l.type === 'error');
          results.push({ check: c.type, passed: errs.length === 0, actual: errs.length });
        } else if (c.type === 'network_no_fail') {
          const fails = this.networkLogs.filter(n => n.failed || n.status >= 400);
          results.push({ check: c.type, passed: fails.length === 0, actual: fails.length });
        } else if (c.type === 'eval_true') {
          const r = await page.evaluate(c.value);
          results.push({ check: c.type, value: c.value, passed: !!r, actual: r });
        } else {
          results.push({ check: c.type, error: 'unknown check type: ' + c.type });
        }
      } catch (e) {
        results.push({ check: c.type, error: e.message });
      }
    }
    return { passed: results.every(r => r.passed !== false), results };
  }

  async report() {
    const page = await this._ensurePage();
    const state = await this.state().catch(() => ({}));
    const perf = await this.perf().catch(() => ({}));
    const consoleErr = this.consoleLogs.filter(l => l.type === 'error').slice(-20);
    const netFail = this.networkLogs.filter(n => n.failed || n.status >= 400).slice(-20);
    const issues = [];
    if (consoleErr.length) issues.push('console errors: ' + consoleErr.length);
    if (netFail.length) issues.push('failed/4xx/5xx requests: ' + netFail.length);
    if (this.pageErrors.length) issues.push('page errors: ' + this.pageErrors.length);
    if (perf.timing && perf.timing.ttfb > 2000) issues.push('slow TTFB: ' + perf.timing.ttfb + 'ms');
    if (perf.timing && perf.timing.load > 10000) issues.push('slow load: ' + perf.timing.load + 'ms');
    return {
      generatedAt: new Date().toISOString(),
      page: state.page,
      issues,
      healthy: issues.length === 0,
      console: { total: state.consoleCount, errorSample: consoleErr },
      network: { total: this.networkLogs.length, failureSample: netFail },
      perf: perf.timing,
      resources: perf.resources,
      pageErrors: this.pageErrors.slice(-10),
    };
  }

  async run({ steps = [] } = {}) {
    const page = await this._ensurePage();
    const results = [];
    for (let i = 0; i < steps.length; i++) {
      const s = steps[i];
      const action = s.action;
      try {
        if (action === 'open') await this.open({ url: s.url, waitMs: s.waitMs || 5000 });
        else if (action === 'click') await this.click({ selector: s.selector, text: s.text, timeout: s.timeout });
        else if (action === 'clickAt') await this.clickAt({ x: s.x, y: s.y });
        else if (action === 'type') await this.type({ selector: s.selector, text: s.text, fill: s.fill });
        else if (action === 'keyboard') await this.keyboard({ text: s.text, key: s.key });
        else if (action === 'wait') await this.wait({ selector: s.selector, timeout: s.timeout || 3000 });
        else if (action === 'eval') { const r = await page.evaluate(s.expression); results.push({ step: i, action, result: r }); continue; }
        else if (action === 'assert') { const r = await this.assert({ checks: [s.check] }); results.push({ step: i, action, result: r }); continue; }
        else if (action === 'screenshot') await this.screenshot({ path: s.path, fullPage: s.fullPage });
        else if (action === 'wheel') await this.wheel({ deltaY: s.deltaY || 300 });
        else if (action === 'log') { results.push({ step: i, action, message: s.message }); continue; }
        else if (action === 'nav') await this.nav({ action: s.navAction || 'reload' });
        else throw new Error('unknown action: ' + action);
        results.push({ step: i, action, ok: true });
      } catch (e) {
        results.push({ step: i, action, ok: false, error: e.message });
        return { completed: i, total: steps.length, success: false, results };
      }
    }
    return { completed: steps.length, total: steps.length, success: true, results };
  }

  async resize({ width, height }) {
    const page = await this._ensurePage();
    const safeWidth = clampInt(width, NaN, 200, 7680);
    const safeHeight = clampInt(height, NaN, 200, 4320);
    if (!Number.isFinite(safeWidth) || !Number.isFinite(safeHeight)) {
      throw new Error('valid width and height are required');
    }
    await page.setViewportSize({ width: safeWidth, height: safeHeight });
    return { viewport: { width: safeWidth, height: safeHeight } };
  }

  async nav({ action }) {
    const page = await this._ensurePage();
    if (action === 'back') await page.goBack({ waitUntil: 'domcontentloaded' });
    else if (action === 'forward') await page.goForward({ waitUntil: 'domcontentloaded' });
    else if (action === 'reload') await page.reload({ waitUntil: 'domcontentloaded' });
    else throw new Error('action must be back|forward|reload');
    await page.waitForTimeout(1500);
    return { action, url: page.url() };
  }

  async dom({ selector, attr = 'outerHTML', limit = 5 }) {
    const page = await this._ensurePage();
    limit = clampInt(limit, 5, 1, 200);
    const r = await page.evaluate(({ sel, at, lim }) => {
      const els = [...document.querySelectorAll(sel)].slice(0, lim);
      return els.map((el) => {
        const rect = el.getBoundingClientRect();
        return {
          tag: el.tagName.toLowerCase(),
          id: el.id,
          cls: typeof el.className === 'string' ? el.className.slice(0, 80) : '',
          text: (el.textContent || '').trim().slice(0, 80),
          visible: rect.width > 0 && rect.height > 0,
          rect: { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) },
          html: at === 'outerHTML' ? el.outerHTML.slice(0, 500) : (el.getAttribute(at) || '').slice(0, 500),
        };
      });
    }, { sel: selector, at: attr, lim: limit });
    return { count: r.length, elements: r };
  }

  async links({ filter = '' } = {}) {
    const page = await this._ensurePage();
    const r = await page.evaluate((flt) => {
      const out = { links: [], images: [], scripts: [], styles: [] };
      for (const a of document.querySelectorAll('a[href]')) {
        const h = a.href;
        if (!flt || h.includes(flt)) out.links.push({ text: (a.textContent || '').trim().slice(0, 50), href: h.slice(0, 150) });
      }
      for (const img of document.querySelectorAll('img[src]')) out.images.push(img.src.slice(0, 150));
      for (const s of document.querySelectorAll('script[src]')) out.scripts.push(s.src.slice(0, 150));
      for (const l of document.querySelectorAll('link[href]')) out.styles.push(l.href.slice(0, 150));
      return out;
    }, filter);
    r.links = r.links.slice(0, 30); r.images = r.images.slice(0, 20); r.scripts = r.scripts.slice(0, 20); r.styles = r.styles.slice(0, 20);
    return r;
  }

  async flutter({ action, label = '', role = '', index = 0 }) {
    const page = await this._ensurePage();
    if (action === 'nodes') {
      const r = await page.evaluate(() => [...document.querySelectorAll('flt-semantics')].map((n) => {
        const rect = n.getBoundingClientRect();
        return {
          role: n.getAttribute('role'),
          label: (n.getAttribute('aria-label') || '').trim().slice(0, 40),
          text: (n.textContent || '').trim().slice(0, 40),
          x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height),
        };
      }).filter((n) => n.role || n.label || n.text));
      return { count: r.length, nodes: r.slice(0, 60) };
    }
    if (action === 'click') {
      const r = await page.evaluate(({ lbl, rl, idx }) => {
        const nodes = [...document.querySelectorAll('flt-semantics')].filter((n) => {
          const t = (n.textContent || '').trim();
          const l = (n.getAttribute('aria-label') || '').trim();
          const role = n.getAttribute('role');
          if (rl && role !== rl) return false;
          return lbl ? (t === lbl || t.includes(lbl) || l.includes(lbl)) : true;
        });
        const target = nodes[idx] || nodes[0];
        if (!target) return { clicked: false, matched: 0 };
        const rect = target.getBoundingClientRect();
        target.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: rect.x + rect.width / 2, clientY: rect.y + rect.height / 2 }));
        return { clicked: true, x: Math.round(rect.x), y: Math.round(rect.y), matched: nodes.length };
      }, { lbl: label, rl: role, idx: index });
      await page.waitForTimeout(800);
      return r;
    }
    if (action === 'enable') {
      const r = await page.evaluate(() => {
        const el = document.querySelector('flt-semantics-placeholder');
        if (!el) return { enabled: false, reason: 'no placeholder (maybe already enabled)' };
        const rect = el.getBoundingClientRect();
        el.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: rect.x + rect.width / 2, clientY: rect.y + rect.height / 2 }));
        return { enabled: true };
      });
      await page.waitForTimeout(1500);
      return r;
    }
    throw new Error('action must be nodes|click|enable');
  }

  async route({ patterns = [], mock = '', status = 200 } = {}) {
    const page = await this._ensurePage();
    if (patterns.length === 0) {
      await page.unrouteAll();
      return { unregistered: true };
    }
    status = clampInt(status, 200, 100, 599);
    for (const p of patterns) {
      await page.route(p, (route) => {
        if (mock) route.fulfill({ status, contentType: 'application/json', body: mock });
        else route.continue();
      });
    }
    return { registered: patterns, mock: !!mock, status };
  }

  async close() {
    if (this.browser) {
      await this.browser.close();
      this.browser = null; this.context = null; this.page = null;
      this.mobileMode = null;
      this.consoleLogs = []; this.networkLogs = []; this.pageErrors = [];
    }
    return { closed: true };
  }

  // ==================== v3.1 新增（参考 mcp-chrome） ====================

  /** 提取页面正文文本（mcp-chrome: chrome_get_web_content） */
  async text({ maxLen = 20000 } = {}) {
    const page = await this._ensurePage();
    maxLen = clampInt(maxLen, 20000, 1, 100000);
    const r = await page.evaluate((ml) => {
      const a = document.querySelector('article') || document.body;
      const t = (a ? a.innerText : '').replace(/\n{3,}/g, '\n\n');
      return { title: document.title, url: location.href, text: t.slice(0, ml), length: t.length };
    }, maxLen).catch((e) => ({ evalErr: String(e) }));
    return r;
  }

  /** 查找可点击元素（mcp-chrome: chrome_get_interactive_elements） */
  async interactive({ limit = 50 } = {}) {
    const page = await this._ensurePage();
    limit = clampInt(limit, 50, 1, 500);
    const r = await page.evaluate((lim) => {
      const els = Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[onclick],[tabindex]'))
        .filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
        .slice(0, lim);
      function sel(n) {
        if (!n || n === document.body) return '';
        if (n.id) return '#' + n.id;
        const p = n.parentElement;
        const s = p ? Array.from(p.children).indexOf(n) + 1 : 1;
        return sel(p) + '>' + n.tagName.toLowerCase() + ':nth-child(' + s + ')';
      }
      return els.map((e) => ({
        tag: e.tagName.toLowerCase(),
        text: (e.innerText || e.value || e.getAttribute('aria-label') || '').trim().slice(0, 80),
        href: e.getAttribute('href') || '',
        type: e.getAttribute('type') || '',
        selector: sel(e).slice(0, 200),
      }));
    }, limit).catch((e) => ({ evalErr: String(e) }));
    return { count: Array.isArray(r) ? r.length : 0, elements: Array.isArray(r) ? r : [] };
  }

  /** 自定义 HTTP GET 请求（mcp-chrome: chrome_network_request） */
  async http({ url, headers = '', timeoutMs = 10000 }) {
    const page = await this._ensurePage();
    const targetUrl = normalizeHttpUrl(url);
    const h = headers ? JSON.parse(headers) : {};
    const timeout = clampInt(timeoutMs, 10000, 1000, 60000);
    const res = await page.request.get(targetUrl, { headers: h, timeout });
    const body = await res.text();
    return {
      status: res.status(),
      url: targetUrl,
      bodyLength: body.length,
      truncated: body.length > 100000,
      body: body.slice(0, 100000),
      headers: res.headers(),
    };
  }
}

const diag = new BrowserDiag();

const toolDefs = {
  browser_open: {
    desc: '打开网页并采集日志（mobile=true 模拟手机浏览器）',
    schema: { url: z.string(), mobile: z.boolean().optional(), waitMs: z.number().optional() },
    run: (p) => diag.open(p),
  },
  browser_state: {
    desc: '当前页面状态摘要：URL/标题/console统计/网络失败数',
    schema: {},
    run: () => diag.state(),
  },
  browser_console: {
    desc: '查看 console 日志（type=error/warning/log 过滤，limit 条数）',
    schema: { type: z.string().optional(), limit: z.number().optional() },
    run: (p) => diag.console(p),
  },
  browser_network: {
    desc: '查看网络请求（onlyFailed=true 只看失败/4xx/5xx）',
    schema: { onlyFailed: z.boolean().optional(), limit: z.number().optional() },
    run: (p) => diag.network(p),
  },
  browser_eval: {
    desc: '在页面执行任意 JS 表达式并返回结果（如检查 Flutter 状态）',
    schema: { expression: z.string() },
    run: (p) => diag.eval(p),
  },
  browser_click: {
    desc: '点击元素（selector=CSS 选择器 或 text=页面文本）',
    schema: { selector: z.string().optional(), text: z.string().optional(), timeout: z.number().optional() },
    run: (p) => diag.click(p),
  },
  browser_click_at: {
    desc: '按坐标点击（Flutter canvas 场景必需）',
    schema: { x: z.number(), y: z.number() },
    run: (p) => diag.clickAt(p),
  },
  browser_keyboard: {
    desc: '键盘输入文本（text）或按键（key 如 Enter/Backspace）',
    schema: { text: z.string().optional(), key: z.string().optional() },
    run: (p) => diag.keyboard(p),
  },
  browser_wheel: {
    desc: '鼠标滚轮滚动（deltaY 正值向下滚动，Flutter 页面滚动）',
    schema: { deltaY: z.number().optional(), deltaX: z.number().optional() },
    run: (p) => diag.wheel(p),
  },
  browser_type: {
    desc: '向输入框输入文本（fill=true 直接赋值，false 模拟键盘）',
    schema: { selector: z.string(), text: z.string(), fill: z.boolean().optional() },
    run: (p) => diag.type(p),
  },
  browser_wait: {
    desc: '等待（selector 出现或等待毫秒数）',
    schema: { selector: z.string().optional(), timeout: z.number().optional() },
    run: (p) => diag.wait(p),
  },
  browser_screenshot: {
    desc: '截图保存到文件（可 fullPage 整页）',
    schema: { path: z.string().optional(), fullPage: z.boolean().optional() },
    run: (p) => diag.screenshot(p),
  },
  browser_save: {
    desc: '下载 URL 内容到本地文件（验证部署产物、对比字节数等）',
    schema: { url: z.string(), path: z.string(), headers: z.string().optional() },
    run: (p) => diag.save(p),
  },
  browser_source: {
    desc: '提取当前页面 HTML 源码（带长度与截断标记）',
    schema: { maxLen: z.number().optional() },
    run: (p) => diag.source(p),
  },
  browser_close: {
    desc: '关闭浏览器（清空日志）',
    schema: {},
    run: () => diag.close(),
  },
  browser_perf: {
    desc: '性能指标：TTFB/DOMContentLoaded/Load/Paint/慢资源/内存',
    schema: {},
    run: () => diag.perf(),
  },
  browser_assert: {
    desc: '页面断言检查：url_contains/text_exists/selector_exists/console_no_error/network_no_fail/eval_true',
    schema: { checks: z.array(z.any()) },
    run: (p) => diag.assert(p),
  },
  browser_report: {
    desc: '一键诊断报告：页面状态+console错误+网络失败+性能+问题清单',
    schema: {},
    run: () => diag.report(),
  },
  browser_run: {
    desc: '执行脚本化步骤：open/click/clickAt/type/keyboard/wait/eval/assert/screenshot/wheel/nav/log',
    schema: { steps: z.array(z.any()) },
    run: (p) => diag.run(p),
  },
  browser_resize: {
    desc: '调整视口尺寸（响应式测试，如 390x844 手机）',
    schema: { width: z.number(), height: z.number() },
    run: (p) => diag.resize(p),
  },
  browser_nav: {
    desc: '导航控制：back/forward/reload',
    schema: { action: z.enum(['back', 'forward', 'reload']) },
    run: (p) => diag.nav(p),
  },
  browser_dom: {
    desc: 'DOM 查询：选择器匹配元素的属性/可见性/坐标/HTML',
    schema: { selector: z.string(), attr: z.string().optional(), limit: z.number().optional() },
    run: (p) => diag.dom(p),
  },
  browser_links: {
    desc: '提取页面链接/图片/脚本/样式资源 URL（可 filter 过滤）',
    schema: { filter: z.string().optional() },
    run: (p) => diag.links(p),
  },
  browser_flutter: {
    desc: 'Flutter Web 辅助：enable 启用语义树 / nodes 列出语义节点 / click 按 label 点击',
    schema: { action: z.enum(['enable', 'nodes', 'click']), label: z.string().optional(), role: z.string().optional(), index: z.number().optional() },
    run: (p) => diag.flutter(p),
  },
  browser_route: {
    desc: '请求拦截：patterns 匹配的请求返回 mock（空数组取消拦截）',
    schema: { patterns: z.array(z.string()), mock: z.string().optional(), status: z.number().optional() },
    run: (p) => diag.route(p),
  },
  browser_text: {
    desc: '提取页面正文文本（mcp-chrome: chrome_get_web_content）',
    schema: { maxLen: z.number().optional() },
    run: (p) => diag.text(p),
  },
  browser_interactive: {
    desc: '查找可点击元素及 CSS 选择器（mcp-chrome: chrome_get_interactive_elements）',
    schema: { limit: z.number().optional() },
    run: (p) => diag.interactive(p),
  },
  browser_http: {
    desc: '自定义 HTTP GET 请求，返回状态码/响应头/正文（mcp-chrome: chrome_network_request）',
    schema: { url: z.string(), headers: z.string().optional(), timeoutMs: z.number().optional() },
    run: (p) => diag.http(p),
  },
};

// ---------- MCP 模式 ----------
async function startMcp() {
  const server = new McpServer({ name: 'browser-diag', version: '3.2.0' });
  for (const [name, def] of Object.entries(toolDefs)) {
    server.tool(name, def.desc, def.schema, async (params) => {
      try {
        const r = await def.run(params || {});
        return { content: [{ type: 'text', text: JSON.stringify(r, null, 2) }] };
      } catch (e) {
        return {
          isError: true,
          content: [{ type: 'text', text: 'ERROR: ' + (e.message || e) }],
        };
      }
    });
  }
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('[browser-diag] MCP stdio server started');
}

// ---------- CLI 模式 ----------
async function cli(tool, jsonParams) {
  const def = toolDefs[tool];
  if (!def) {
    console.error('unknown tool. available: ' + Object.keys(toolDefs).join(', '));
    process.exitCode = 2;
    return;
  }
  try {
    const params = jsonParams ? JSON.parse(jsonParams) : {};
    const r = await def.run(params);
    console.log(JSON.stringify(r, null, 2));
  } catch (e) {
    console.error('ERROR: ' + (e.message || e));
    process.exitCode = 1;
  }
  await diag.close().catch(() => {});
}

// ---------- HTTP 常驻模式 ----------
async function readRequestBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_HTTP_BODY_BYTES) {
      const error = new Error('request body too large');
      error.statusCode = 413;
      throw error;
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function sendJson(res, status, value, corsOrigin = '') {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (corsOrigin) res.setHeader('Access-Control-Allow-Origin', corsOrigin);
  res.writeHead(status);
  res.end(JSON.stringify(value));
}

export function startHttp({
  port = DEFAULT_HTTP_PORT,
  host = DEFAULT_HTTP_HOST,
  token = process.env.BROWSERDIAG_TOKEN || '',
  corsOrigin = process.env.BROWSERDIAG_CORS_ORIGIN || '',
} = {}) {
  const generatedToken = !token;
  const authToken = token || crypto.randomBytes(24).toString('base64url');
  if (authToken.length < 16) throw new Error('BROWSERDIAG_TOKEN must be at least 16 characters');

  const server = http.createServer(async (req, res) => {
    if (req.method === 'OPTIONS') {
      if (corsOrigin) {
        res.setHeader('Access-Control-Allow-Origin', corsOrigin);
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-BrowserDiag-Token');
      }
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return;
    }
    if (req.method !== 'GET' && req.method !== 'POST') {
      sendJson(res, 405, { error: 'method not allowed' }, corsOrigin);
      return;
    }
    if (!isAuthorized(req.headers, authToken)) {
      sendJson(res, 401, { error: 'unauthorized' }, corsOrigin);
      return;
    }

    const requestUrl = new URL(req.url || '/', 'http://localhost');
    const match = /^\/api\/([a-z0-9_]+)$/.exec(requestUrl.pathname);
    const tool = match?.[1] || '';
    const def = toolDefs[tool];
    if (!def) {
      sendJson(
        res,
        404,
        { error: 'unknown tool: ' + tool, available: Object.keys(toolDefs) },
        corsOrigin
      );
      return;
    }

    let params = Object.fromEntries(requestUrl.searchParams);
    if (req.method === 'POST') {
      try {
        const body = await readRequestBody(req);
        if (body) {
          const parsed = JSON.parse(body);
          if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
            throw new Error('JSON body must be an object');
          }
          params = { ...params, ...parsed };
        }
      } catch (error) {
        sendJson(res, error?.statusCode || 400, { error: error?.message || 'invalid request' }, corsOrigin);
        return;
      }
    }

    try {
      const result = await def.run(params);
      sendJson(res, 200, result, corsOrigin);
    } catch (error) {
      sendJson(res, 500, { error: error?.message || String(error) }, corsOrigin);
    }
  });

  server.requestTimeout = 70000;
  server.headersTimeout = 10000;
  server.listen(port, host, () => {
    const address = server.address();
    const actualPort = typeof address === 'object' && address ? address.port : port;
    console.error('[browser-diag] HTTP server on http://' + host + ':' + actualPort);
    if (generatedToken) {
      console.error('[browser-diag] generated API token: ' + authToken);
    }
  });
  return { server, token: authToken };
}

export async function main(args = process.argv.slice(2)) {
  if (args[0] === '--cli') {
    await cli(args[1], args[2]);
  } else if (args[0] === '--http') {
    const options = parseHttpOptions(args.slice(1));
    startHttp(options);
  } else if (!args[0]) {
    await startMcp();
  } else {
    throw new Error('usage: server.js [--cli <tool> <json> | --http [port] [--host host] [--port port]]');
  }
}

const entryUrl = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : '';
if (import.meta.url === entryUrl) {
  try {
    await main();
  } catch (error) {
    console.error('ERROR: ' + (error?.message || error));
    process.exitCode = 1;
  }
}
