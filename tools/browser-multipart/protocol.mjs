// No SDK, provider credentials, implicit retries or storage control-plane calls.
export class HttpFailure extends Error {
  constructor(status) { super(`HTTP ${status}`); this.status = status; }
}

export class AuthClient {
  constructor({ fetcher = fetch, storage = sessionStorage, locks = navigator.locks } = {}) {
    this.fetcher = fetcher;
    this.storage = storage;
    this.locks = locks;
    this.csrf = storage.getItem('vaultdrive-harness-csrf');
  }
  forget() {
    this.access = null;
    this.csrf = null;
    this.storage.removeItem('vaultdrive-harness-csrf');
  }
  async accept(response) {
    if (!response.ok) throw new HttpFailure(response.status);
    const body = await response.json();
    const csrf = response.headers.get('X-VaultDrive-CSRF');
    if (!body.accessToken || !csrf) throw new Error('Missing authentication contract');
    this.access = body.accessToken;
    this.csrf = csrf;
    this.storage.setItem('vaultdrive-harness-csrf', csrf);
    return body;
  }
  async login(email, password) {
    this.forget();
    return this.accept(await this.fetcher('/api/v1/auth/login', {
      method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      headers: { 'Content-Type': 'application/json', 'X-VaultDrive-Client': '1' },
      body: JSON.stringify({ email, password })
    }));
  }
  refresh() {
    if (this.refreshing) return this.refreshing;
    if (!this.csrf || !this.locks) return Promise.reject(new Error('Login and Web Locks required'));
    // One flight in this tab, plus origin-wide serialization with other harness tabs.
    // Read the current browser-managed cookie only when the lock is acquired.
    this.refreshing = this.locks.request('vaultdrive-harness-refresh', async () => {
      try {
        return await this.accept(await this.fetcher('/api/v1/auth/refresh', {
          method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
          headers: { 'X-VaultDrive-CSRF': this.csrf }
        }));
      } catch (error) {
        // A lost response may have consumed the cookie. Reauthenticate; never retry.
        this.forget();
        throw error;
      }
    }).finally(() => { this.refreshing = null; });
    return this.refreshing;
  }
  async raw(path, { method = 'GET', body, headers = {} } = {}) {
    if (!/^\/api\/v1\//.test(path)) throw new Error('API path required');
    if (!this.access) throw new Error('Login or refresh first');
    const response = await this.fetcher(path, {
      method, credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      headers: { ...headers, Authorization: `Bearer ${this.access}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    if (!response.ok) throw new HttpFailure(response.status);
    return response;
  }
  async api(path, options) {
    const response = await this.raw(path, options);
    return response.status === 204 ? null : response.json();
  }
  async logout() {
    await this.raw('/api/v1/auth/logout', { method: 'POST', headers: { 'X-VaultDrive-CSRF': this.csrf } });
    this.forget();
  }
}

export async function sha256(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return btoa(String.fromCharCode(...digest));
}

export async function expectations(file, session) {
  if (file.size !== session.declaredSize || file.size > 32 * 1024 * 1024) {
    throw new Error('Select the original file (harness maximum 32 MiB)');
  }
  const parts = [];
  for (let number = 1; number <= session.partCount; number++) {
    const start = (number - 1) * session.partSize;
    const bytes = await file.slice(start, start + session.partSize).arrayBuffer();
    parts.push({ partNumber: number, length: bytes.byteLength, sha256: await sha256(bytes) });
  }
  return parts;
}

export function browserHeaders(signed, bytes, garageOrigin) {
  const url = new URL(signed.url);
  if (url.protocol !== 'https:' || url.origin !== garageOrigin || url.username || url.password) {
    throw new Error('Capability must use the configured HTTPS Garage origin');
  }
  const headers = Object.fromEntries(Object.entries(signed.requiredHeaders).map(([key, values]) => {
    if (values.length !== 1) throw new Error('Unexpected signed header values');
    return [key.toLowerCase(), values[0]];
  }));
  if (Object.keys(headers).sort().join(',') !== 'content-length,host,x-amz-checksum-sha256'
      || headers.host !== url.host || headers['content-length'] !== String(bytes.byteLength)
      || !/^[A-Za-z0-9+/]{43}=$/.test(headers['x-amz-checksum-sha256'])) {
    throw new Error('Signed header contract mismatch');
  }
  // Host and Content-Length are forbidden fetch headers. A fixed-size ArrayBuffer
  // supplies exact framing; setting either header in JavaScript is incorrect.
  return { 'x-amz-checksum-sha256': headers['x-amz-checksum-sha256'] };
}

export async function uploadPart(signed, bytes, garageOrigin, { fetcher = fetch, tamper } = {}) {
  const headers = browserHeaders(signed, bytes, garageOrigin);
  if (await sha256(bytes) !== headers['x-amz-checksum-sha256']) throw new Error('Local checksum mismatch');
  const url = new URL(signed.url);
  if (tamper === 'signature') {
    const signature = url.searchParams.get('X-Amz-Signature');
    if (!signature) throw new Error('Missing signature');
    url.searchParams.set('X-Amz-Signature', (signature[0] === '0' ? '1' : '0') + signature.slice(1));
  } else if (tamper === 'checksum') {
    headers['x-amz-checksum-sha256'] = btoa(String.fromCharCode(...new Uint8Array(32).fill(255)));
  } else if (tamper === 'missing-checksum') {
    delete headers['x-amz-checksum-sha256'];
  }
  let body = bytes;
  if (tamper === 'body') {
    body = bytes.slice(0);
    new Uint8Array(body)[0] ^= 1;
  } else if (tamper === 'length') {
    body = new Uint8Array(bytes.byteLength + 1);
    body.set(new Uint8Array(bytes));
  }
  const response = await fetcher(tamper === 'signature' ? url.toString() : signed.url, {
    method: 'PUT', body, headers, mode: 'cors', credentials: 'omit',
    redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer'
  });
  // A network/CORS failure is uncertain, never success and never an automatic retry.
  return { status: response.status, etag: response.headers.get('ETag'),
    checksum: response.headers.get('x-amz-checksum-sha256') };
}

export async function listAll(client, id) {
  const parts = [];
  let marker = 0;
  // One-part pages deliberately exercise pagination; bound the entire traversal.
  for (let count = 0; count < 640; count++) {
    const page = await client.api(`/api/v1/upload-sessions/${id}/parts?marker=${marker}&limit=1`);
    parts.push(...page.parts);
    if (page.nextMarker === null) return parts;
    if (page.nextMarker <= marker || page.nextMarker > 640) throw new Error('Invalid resume marker');
    marker = page.nextMarker;
  }
  throw new Error('Resume page bound exceeded');
}

export function missingParts(expected, observed) {
  const seen = new Set();
  for (const part of observed) {
    const registered = expected.find(p => p.partNumber === part.partNumber);
    if (seen.has(part.partNumber) || !registered || registered.length !== part.size
        || registered.sha256 !== part.sha256 || !part.etag) throw new Error('Resume evidence mismatch');
    seen.add(part.partNumber);
  }
  return expected.filter(part => !seen.has(part.partNumber));
}
