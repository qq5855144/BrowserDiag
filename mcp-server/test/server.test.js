import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';

import {
  BrowserDiag,
  isAuthorized,
  normalizeHttpUrl,
  parseHttpOptions,
  startHttp,
} from '../server.js';

test('normalizeHttpUrl only accepts HTTP(S)', () => {
  assert.equal(normalizeHttpUrl('https://example.com/a'), 'https://example.com/a');
  assert.throws(() => normalizeHttpUrl('file:///etc/passwd'), /only http\(s\)/);
  assert.throws(() => normalizeHttpUrl('not a url'), /invalid url/);
});

test('parseHttpOptions supports positional and named options', () => {
  assert.deepEqual(
    parseHttpOptions(['9999', '--host', '127.0.0.1'], {}),
    { host: '127.0.0.1', port: 9999 }
  );
  assert.deepEqual(
    parseHttpOptions(['--port', '8888'], { BROWSERDIAG_HOST: 'localhost' }),
    { host: 'localhost', port: 8888 }
  );
  assert.throws(() => parseHttpOptions(['--port', '70000'], {}), /invalid HTTP port/);
});

test('HTTP token comparison accepts bearer and explicit token headers', () => {
  const token = '0123456789abcdef01234567';
  assert.equal(isAuthorized({ authorization: 'Bearer ' + token }, token), true);
  assert.equal(isAuthorized({ 'x-browserdiag-token': token }, token), true);
  assert.equal(isAuthorized({ authorization: 'Bearer wrong' }, token), false);
  assert.equal(isAuthorized({}, token), false);
});

test('resize uses Page.setViewportSize with bounded values', async () => {
  const diag = new BrowserDiag();
  let viewport = null;
  diag._ensurePage = async () => ({
    setViewportSize: async (value) => { viewport = value; },
  });
  const result = await diag.resize({ width: 390, height: 844 });
  assert.deepEqual(viewport, { width: 390, height: 844 });
  assert.deepEqual(result, { viewport: { width: 390, height: 844 } });
});

test('source returns explicit truncation metadata', async () => {
  const diag = new BrowserDiag();
  diag._ensurePage = async () => ({
    content: async () => '<html>abcdef</html>',
    url: () => 'https://example.com/',
  });
  const result = await diag.source({ maxLen: 8 });
  assert.equal(result.url, 'https://example.com/');
  assert.equal(result.htmlLength, 19);
  assert.equal(result.truncated, true);
  assert.equal(result.html, '<html>ab');
});

test('HTTP mode rejects unauthenticated requests before tool dispatch', async (t) => {
  const token = '0123456789abcdef01234567';
  const { server } = startHttp({ host: '127.0.0.1', port: 0, token });
  await once(server, 'listening');
  t.after(() => server.close());
  const address = server.address();
  assert.equal(typeof address, 'object');
  const url = 'http://127.0.0.1:' + address.port + '/api/not_a_tool';

  const unauthorized = await fetch(url);
  assert.equal(unauthorized.status, 401);

  const authorized = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal(authorized.status, 404);

  const closeResponse = await fetch(
    'http://127.0.0.1:' + address.port + '/api/browser_close',
    { headers: { Authorization: 'Bearer ' + token } }
  );
  assert.equal(closeResponse.status, 200);
  assert.deepEqual(await closeResponse.json(), { closed: true });

  const oversized = await fetch(
    'http://127.0.0.1:' + address.port + '/api/browser_close',
    {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ value: 'x'.repeat(300 * 1024) }),
    }
  );
  assert.equal(oversized.status, 413);
});
