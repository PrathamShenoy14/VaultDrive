import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { handler, settings } from './serve.mjs';

const config = settings({ GARAGE_ORIGIN: 'https://garage.example.test' });
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
const close = server => new Promise(resolve => server.close(resolve));
function request(port, path, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path, method,
      headers: { host: 'localhost:9443', ...headers } }, res => {
      let text = '';
      res.on('data', chunk => { text += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: text }));
    });
    req.on('error', reject); req.end(body);
  });
}

test('harness settings require HTTPS, loopback API and bare origins', () => {
  assert.equal(config.origin, 'https://localhost:9443');
  for (const change of [
    { GARAGE_ORIGIN: 'http://garage.example.test' },
    { GARAGE_ORIGIN: 'https://user:secret@garage.example.test' },
    { HARNESS_ORIGIN: 'http://localhost:9443' },
    { HARNESS_ORIGIN: 'https://public.example.test' },
    { API_UPSTREAM: 'http://remote.example.test' },
    { GARAGE_ORIGIN: 'https://garage.example.test/path' }
  ]) assert.throws(() => settings({ GARAGE_ORIGIN: config.garageOrigin, ...change }));
});

test('static allowlist and CSP exclude secrets, traversal and foreign origins', async () => {
  const server = http.createServer(handler(config));
  const port = await listen(server);
  try {
    const index = await request(port, '/');
    assert.equal(index.status, 200);
    assert.match(index.headers['content-security-policy'], /connect-src 'self' https:\/\/garage.example.test/);
    assert.equal(index.headers['cache-control'], 'no-store');
    assert.equal((await request(port, '/config')).body, JSON.stringify({ garageOrigin: config.garageOrigin }));
    for (const path of ['/.env.local.ps1', '/../../backend/.env.local.ps1', '/serve.mjs']) {
      assert.equal((await request(port, path)).status, 404);
    }
    assert.equal((await request(port, '/', { headers: { origin: 'https://evil.example.test' } })).status, 403);
    assert.equal((await request(port, '/', { headers: { host: 'evil.example.test' } })).status, 403);
    assert.equal((await request(port, '/', { method: 'POST' })).status, 405);
    assert.equal((await request(port, '/runbook')).status, 200);
  } finally { await close(server); }
});

test('proxy forwards cookie/CSRF unchanged and preserves Secure HttpOnly cookie response', async () => {
  let count = 0;
  const upstream = http.createServer((req, res) => {
    count++;
    assert.equal(req.url, '/api/v1/auth/refresh');
    assert.equal(req.headers.cookie, '__Host-vaultdrive-refresh=fixture');
    assert.equal(req.headers['x-vaultdrive-csrf'], 'csrf');
    assert.equal(req.headers['x-unrelated-secret'], undefined);
    res.setHeader('Set-Cookie', '__Host-vaultdrive-refresh=rotated; Path=/; Secure; HttpOnly; SameSite=Strict');
    res.setHeader('X-VaultDrive-CSRF', 'csrf');
    res.end('{}');
  });
  const upstreamPort = await listen(upstream);
  const server = http.createServer(handler({ ...config, upstream: new URL(`http://127.0.0.1:${upstreamPort}`) }));
  const port = await listen(server);
  try {
    const result = await request(port, '/api/v1/auth/refresh', { method: 'POST', headers: {
      cookie: '__Host-vaultdrive-refresh=fixture', 'x-vaultdrive-csrf': 'csrf', 'x-unrelated-secret': 'omit'
    } });
    assert.equal(count, 1);
    assert.equal(result.status, 200);
    assert.match(result.headers['set-cookie'][0], /Secure; HttpOnly; SameSite=Strict/);
    assert.equal(result.headers['x-vaultdrive-csrf'], 'csrf');
    assert.equal(result.headers['access-control-allow-origin'], undefined);
    assert.equal((await request(port, '/api/v1/auth/refresh', { method: 'POST', headers: {
      origin: 'https://evil.example.test'
    } })).status, 403);
    assert.equal(count, 1);
  } finally { await close(server); await close(upstream); }
});

test('proxy returns 502 on uncertain upstream outcome without another dispatch', async () => {
  let count = 0;
  const upstream = http.createServer(req => { count++; req.socket.destroy(); });
  const upstreamPort = await listen(upstream);
  const server = http.createServer(handler({ ...config, upstream: new URL(`http://127.0.0.1:${upstreamPort}`) }));
  const port = await listen(server);
  try {
    assert.equal((await request(port, '/api/v1/upload-sessions/id/complete', { method: 'POST' })).status, 502);
    assert.equal(count, 1);
  } finally { await close(server); await close(upstream); }
});
