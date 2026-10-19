import http from 'node:http';
import https from 'node:https';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = path.dirname(fileURLToPath(import.meta.url));
const assets = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/app.mjs': ['app.mjs', 'text/javascript; charset=utf-8'],
  '/protocol.mjs': ['protocol.mjs', 'text/javascript; charset=utf-8'],
  '/style.css': ['style.css', 'text/css; charset=utf-8'],
  '/runbook': ['../../docs/browser-multipart-interoperability.md', 'text/plain; charset=utf-8']
};

export function settings(env) {
  const origin = new URL(env.HARNESS_ORIGIN || 'https://localhost:9443');
  const garage = new URL(env.GARAGE_ORIGIN);
  const upstream = new URL(env.API_UPSTREAM || 'http://127.0.0.1:8080');
  for (const url of [origin, garage, upstream]) {
    if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
      throw new Error('Origins must have no credentials, path, query or fragment');
    }
  }
  if (origin.protocol !== 'https:' || origin.hostname !== 'localhost' || garage.protocol !== 'https:'
      || !['http:', 'https:'].includes(upstream.protocol)
      || !['localhost', '127.0.0.1', '[::1]'].includes(upstream.hostname)) {
    throw new Error('Local HTTPS harness, HTTPS Garage and loopback API required');
  }
  return { origin: origin.origin, garageOrigin: garage.origin, upstream,
    port: Number(origin.port || 443) };
}

// Exposed separately for loopback HTTP transport tests; the executable uses HTTPS only.
export function handler(config) {
  return async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self' ${config.garageOrigin}; base-uri 'none'; frame-ancestors 'none'; form-action 'none'`);
    if (request.headers.host !== new URL(config.origin).host
        || (request.headers.origin && request.headers.origin !== config.origin)) {
      response.writeHead(403).end(); return;
    }
    if (request.url.startsWith('/api/v1/')) {
      if (!['GET', 'POST', 'PATCH', 'DELETE'].includes(request.method)) { response.writeHead(405).end(); return; }
      const headers = {};
      for (const name of ['authorization', 'cookie', 'content-type', 'content-length', 'x-vaultdrive-csrf',
        'x-vaultdrive-client', 'idempotency-key', 'if-match', 'accept']) {
        if (request.headers[name]) headers[name] = request.headers[name];
      }
      const transport = config.upstream.protocol === 'https:' ? https : http;
      // Fixed upstream plus a relative path. No redirects, retries or access logging.
      const outgoing = transport.request({ protocol: config.upstream.protocol,
        hostname: config.upstream.hostname.replace(/^\[|\]$/g, ''),
        port: config.upstream.port, path: request.url, method: request.method, headers }, incoming => {
        response.statusCode = incoming.statusCode;
        for (const name of ['content-type', 'content-length', 'content-disposition', 'set-cookie',
          'x-vaultdrive-csrf', 'etag', 'location']) {
          if (incoming.headers[name]) response.setHeader(name, incoming.headers[name]);
        }
        incoming.on('error', () => response.destroy());
        incoming.pipe(response);
      });
      outgoing.setTimeout(60000, () => outgoing.destroy());
      outgoing.on('error', () => {
        if (!response.headersSent) response.writeHead(502).end();
        else response.destroy();
      });
      request.on('aborted', () => outgoing.destroy());
      response.on('close', () => { if (!response.writableFinished) outgoing.destroy(); });
      request.pipe(outgoing);
      return;
    }
    if (request.method !== 'GET') { response.writeHead(405).end(); return; }
    if (request.url === '/config') {
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ garageOrigin: config.garageOrigin })); return;
    }
    const asset = assets[request.url];
    if (!asset) { response.writeHead(404).end(); return; }
    try {
      const bytes = await readFile(path.join(directory, asset[0]));
      response.setHeader('Content-Type', asset[1]);
      response.end(bytes);
    } catch { response.writeHead(500).end(); }
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const config = settings(process.env);
    const server = https.createServer({ cert: await readFile(process.env.TLS_CERT),
      key: await readFile(process.env.TLS_KEY) }, handler(config));
    server.requestTimeout = 70000;
    server.listen(config.port, '127.0.0.1', () => {
      console.log(`Harness: ${config.origin}; Garage: ${config.garageOrigin}; loopback API proxy; no request logging`);
    });
    server.on('error', () => { console.error('Harness listener failed'); process.exitCode = 1; });
  } catch { console.error('Harness startup failed: check HTTPS origins, loopback upstream and TLS files'); process.exitCode = 1; }
}
