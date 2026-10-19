import { AuthClient, HttpFailure, expectations, listAll, missingParts, sha256, uploadPart } from './protocol.mjs';

const $ = id => document.getElementById(id);
const config = await (await fetch('/config', { cache: 'no-store' })).json();
const records = [];
let refreshCalls = 0;
const client = new AuthClient({ fetcher: (...args) => {
  if (args[0] === '/api/v1/auth/refresh') refreshCalls++;
  return fetch(...args);
} });
let generated, session, retained;
const dispatched = new Set();
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const record = (scenario, outcome, detail = '') => {
  records.push({ time: new Date().toISOString(), scenario, outcome, detail });
  $('results').textContent = records.map(r => `${r.time} ${r.outcome} ${r.scenario} ${r.detail}`).join('\n');
};
const file = () => {
  const selected = generated || $('file').files[0];
  assert(selected && selected.size > 0 && selected.size <= 32 * 1024 * 1024, 'Select a file up to 32 MiB');
  return selected;
};
const id = () => {
  const value = $('session').value.trim();
  assert(/^[0-9a-f-]{36}$/i.test(value), 'Session UUID required');
  return value;
};
const path = suffix => `/api/v1/upload-sessions/${id()}${suffix}`;
async function read() {
  session = await client.api(path(''));
  return session;
}
async function plan() { return expectations(file(), await read()); }
async function sign(parts) {
  return client.api(path('/part-urls'), { method: 'POST', body: {
    parts: parts.map(({ partNumber, length, sha256: digest }) => ({ partNumber, length, sha256: digest }))
  } });
}
async function firstCapability() {
  const expected = await plan();
  const [capability] = await sign([expected[0]]);
  retained = { ...capability, sessionId: id() };
  return retained;
}
async function bytesFor(part) {
  return file().slice((part.partNumber - 1) * session.partSize,
    (part.partNumber - 1) * session.partSize + part.length).arrayBuffer();
}
async function expectHttp(status, action) {
  try { await action(); } catch (error) {
    if (error instanceof HttpFailure && error.status === status) return;
    throw error;
  }
  throw new Error(`Expected HTTP ${status}`);
}
async function once(operation, action) {
  const key = `${id()}:${operation}`;
  assert(!dispatched.has(key), 'Already dispatched in this tab; use read/recovery');
  dispatched.add(key); // Retain even after a lost response. No automatic resubmission.
  return action();
}
async function transfer(firstOnly) {
  const expected = await plan();
  const observed = await listAll(client, id());
  const missing = missingParts(expected, observed);
  for (const part of firstOnly ? missing.slice(0, 1) : missing) {
    const [capability] = await sign([part]);
    const wire = await uploadPart(capability.upload, await bytesFor(part), config.garageOrigin);
    assert(wire.status === 200, 'UploadPart rejected');
    assert(wire.etag && wire.checksum === part.sha256, 'CORS exposure or checksum response mismatch');
    record('UploadPart', 'PASS', `part=${part.partNumber}; ETag and SHA-256 readable`);
  }
  const after = await listAll(client, id());
  const remaining = missingParts(expected, after);
  assert(firstOnly || remaining.length === 0, 'Resume incomplete');
  return `listed=${after.length}; missing=${remaining.length}`;
}
function bind(name, action) {
  $(name).addEventListener('click', async () => {
    if (document.body.dataset.busy) return;
    document.body.dataset.busy = '1';
    document.querySelectorAll('button').forEach(button => { button.disabled = true; });
    try { record(name, 'PASS', await action() || ''); }
    catch (error) {
      // Provider bodies, credentials and URLs never enter the result panel/export.
      record(name, 'FAIL', error instanceof HttpFailure ? `HTTP ${error.status}` :
        error instanceof TypeError ? 'Network/CORS failure; outcome uncertain. Read authoritative state.' :
          'Contract/precondition failed. See runbook; no automatic retry.');
    } finally {
      delete document.body.dataset.busy;
      document.querySelectorAll('button').forEach(button => { button.disabled = false; });
    }
  });
}

$('environment').textContent = `Origin: ${location.origin}; Garage: ${config.garageOrigin}; secure context: ${isSecureContext}`;
$('key').value = sessionStorage.getItem('vaultdrive-harness-key') || crypto.randomUUID();
$('session').value = sessionStorage.getItem('vaultdrive-harness-session') || '';
$('file').addEventListener('change', () => { generated = null; retained = null; });
assert(isSecureContext && location.protocol === 'https:' && navigator.locks, 'HTTPS and Web Locks required');

bind('login', async () => {
  const password = $('password').value;
  $('password').value = '';
  await client.login($('email').value.trim(), password);
  await client.api('/api/v1/users/me');
  return 'Client header accepted; bearer profile accepted';
});
bind('auth-negative', async () => {
  const response = await fetch('/api/v1/auth/login', { method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert(response.status === 403, 'Missing client header must fail');
});
bind('csrf-negative', async () => {
  for (const headers of [{}, { 'X-VaultDrive-CSRF': 'A'.repeat(43) }]) {
    const response = await fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'same-origin', headers });
    assert(response.status === 403, 'Missing/wrong CSRF must fail without rotating');
  }
  await client.refresh();
  return 'Missing/wrong CSRF rejected; valid refresh still succeeds';
});
bind('refresh', async () => { await client.refresh(); await client.api('/api/v1/users/me'); });
bind('refresh-burst', async () => {
  const before = refreshCalls;
  await Promise.all([client.refresh(), client.refresh(), client.refresh()]);
  assert(refreshCalls === before + 1, 'Refresh must have one network flight');
  await client.api('/api/v1/users/me');
  return '3 callers; 1 refresh request; rotated HttpOnly cookie accepted';
});
bind('profile', async () => {
  await client.api('/api/v1/users/me');
  const cookieOnly = await fetch('/api/v1/users/me', { credentials: 'same-origin' });
  assert(cookieOnly.status === 401, 'Cookie alone must not authorize API');
});
bind('logout', async () => {
  const token = client.access;
  await client.logout();
  const stale = await fetch('/api/v1/users/me', { headers: { Authorization: `Bearer ${token}` } });
  assert(stale.status === 401, 'Logged-out JWT must fail');
});
bind('fixture', () => {
  const bytes = new Uint8Array(16 * 1024 * 1024 + 37);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  generated = new File([bytes], `browser-${$('key').value.slice(0, 8)}-three-parts.bin`, { type: 'application/octet-stream' });
  retained = null;
  return 'Deterministic 16 MiB + 37 byte fixture selected';
});
bind('reserve', async () => {
  const selected = file();
  const key = $('key').value;
  sessionStorage.setItem('vaultdrive-harness-key', key);
  session = await client.api('/api/v1/upload-sessions', { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { folderId: $('folder').value.trim() || null, name: selected.name,
      size: selected.size, contentType: selected.type || 'application/octet-stream' } });
  $('session').value = session.id;
  sessionStorage.setItem('vaultdrive-harness-session', session.id);
  assert(session.state === 'RESERVED', 'Reservation must be RESERVED');
  const me = await client.api('/api/v1/users/me');
  assert(session.initiatorUserId === me.userId, 'Initiator mismatch');
  return `state=${session.state}; parts=${session.partCount}; delegated=${session.ownerId !== me.userId}`;
});
bind('read', async () => {
  const value = await read();
  return `state=${value.state}; completion=${value.completion?.disposition || 'none'}; expires=${value.expiresAt}`;
});
bind('initiate', async () => {
  session = await once('initiate', () => client.api(path('/initiate'), { method: 'POST' }));
  assert(session.state === 'OPEN', 'Initiation pending; read retained state, never repeat dispatch');
});
bind('first', () => transfer(true));
bind('resume', () => transfer(false));
bind('list', async () => {
  const observed = await listAll(client, id());
  const remaining = missingParts(await plan(), observed);
  return `listed=${observed.length}; missing=${remaining.length}`;
});
bind('refresh-url', async () => {
  const before = await firstCapability();
  const [after] = await sign([before.part]);
  assert(after.part.registrationId === before.part.registrationId && after.part.sha256 === before.part.sha256
    && after.part.length === before.part.length, 'Immutable registration changed');
  retained = { ...after, sessionId: id() };
  return `Registration retained; URL deadline=${after.upload.expiresAt}`;
});
bind('negative', async () => {
  const capability = await firstCapability();
  for (const tamper of ['signature', 'checksum', 'missing-checksum', 'body', 'length']) {
    const wire = await uploadPart(capability.upload, await bytesFor(capability.part), config.garageOrigin, { tamper });
    assert([400, 403].includes(wire.status), 'Invalid capability must be rejected');
    record(`invalid-${tamper}`, 'PASS', `HTTP ${wire.status}`);
  }
});
bind('expired-url', async () => {
  assert(retained && retained.sessionId === id(), 'Retain a URL with refresh first');
  assert(Date.now() > Date.parse(retained.upload.expiresAt) + 5000, 'Wait until URL deadline plus clock tolerance');
  await read();
  const wire = await uploadPart(retained.upload, await bytesFor(retained.part), config.garageOrigin);
  assert(wire.status === 403, 'Native expired URL must fail; do not override signing clocks');
  return 'Retained native production signature rejected after expiry';
});
bind('complete', async () => {
  const result = await once('complete', () => client.api(path('/complete'), { method: 'POST' }));
  assert(result.completion?.disposition === 'READY', 'Completion pending; use read/recover explicitly');
});
bind('recover', async () => {
  const result = await client.api(path('/recover'), { method: 'POST' });
  return `disposition=${result.completion?.disposition || 'pending'}`;
});
bind('download', async () => {
  await read();
  const response = await client.raw(`/api/v1/files/${session.fileId}/download`);
  assert(response.headers.get('Content-Disposition')?.startsWith('attachment'), 'Download must be an attachment');
  const bytes = await response.arrayBuffer();
  assert(bytes.byteLength === file().size && await sha256(bytes) === await sha256(await file().arrayBuffer()),
    'Downloaded bytes differ');
  return `Exact bytes verified; size=${bytes.byteLength}`;
});
bind('cancel', async () => {
  const result = await once('cancel', () => client.api(path('/cancel'), { method: 'POST' }));
  assert(result.disposition === 'CANCELLED', 'Cancellation pending; inspect retained state');
  await expectHttp(409, () => client.api(path('/initiate'), { method: 'POST' }));
  await expectHttp(409, () => sign([retained?.part || { partNumber: 1, length: 1, sha256: 'A'.repeat(43) + '=' }]));
  return 'Cancellation acknowledged; new initiation/part authority denied; no reclamation claim';
});
bind('cancel-read', async () => {
  const result = await client.api(path('/cancellation'));
  return `cause=${result.cause}; disposition=${result.disposition}`;
});
bind('expired-session', async () => {
  const expected = await plan();
  assert(Date.now() > Date.parse(session.expiresAt), 'Wait for immutable 24-hour deadline');
  // OPEN initiation replays return the retained OPEN snapshot without storage I/O.
  // Only an uninitiated RESERVED session should attempt this rejection check.
  if (session.state === 'RESERVED') {
    await expectHttp(409, () => client.api(path('/initiate'), { method: 'POST' }));
  }
  await expectHttp(409, () => sign([expected[0]]));
  await expectHttp(409, () => listAll(client, id()));
  return 'Expired session remains readable; new initiation (if RESERVED), sign/list denied. Internal expiry is separate.';
});
bind('revoked', async () => {
  // Use a previously authorized Editor session and revoke its only effective grant externally.
  assert(retained && retained.sessionId === id(), 'Authorize first part before revocation');
  await expectHttp(404, () => read());
  await expectHttp(404, () => sign([retained.part]));
  await expectHttp(404, () => listAll(client, id()));
  for (const operation of ['initiate', 'complete', 'cancel']) {
    await expectHttp(404, () => client.api(path(`/${operation}`), { method: 'POST' }));
  }
  const folderId = $('folder').value.trim();
  assert(folderId, 'Original shared destination folder required');
  await expectHttp(404, () => client.api('/api/v1/upload-sessions', { method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() }, body: {
      folderId, name: 'revoked-browser-probe.bin', size: 1, contentType: 'application/octet-stream'
    } }));
  await expectHttp(404, () => client.raw(`/api/v1/files/${session.fileId}/download`));
  return 'Current Editor authority revoked: session operations, new reservation and download return 404';
});
bind('export', () => {
  const blob = new Blob([JSON.stringify({ origin: location.origin, garageOrigin: config.garageOrigin,
    userAgent: navigator.userAgent, secureContext: isSecureContext, records }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = 'browser-multipart-results.json'; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
