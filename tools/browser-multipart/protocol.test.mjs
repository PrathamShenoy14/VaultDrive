import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AuthClient, HttpFailure, browserHeaders, expectations, listAll, missingParts, sha256, uploadPart,
  verifyPartEvidence } from './protocol.mjs';

const bytes = new TextEncoder().encode('abc').buffer;
const digest = 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=';
const origin = 'https://garage.example.test';
function signed() {
  return { url: `${origin}/bucket/key?X-Amz-Signature=abc`, requiredHeaders: {
    host: ['garage.example.test'], 'content-length': ['3'], 'x-amz-checksum-sha256': [digest]
  } };
}
function storage() {
  const values = new Map();
  return { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}
const credentials = () => Response.json({ accessToken: 'access', expiresIn: 1800 }, { headers: { 'X-VaultDrive-CSRF': 'csrf' } });
const locks = { request: async (_name, action) => action() };
const auth = fetcher => new AuthClient({ fetcher, storage: storage(), locks });

test('browser response evidence must match backend opaque ETag, checksum and size exactly', () => {
  const part = { partNumber: 1, length: 3, sha256: digest };
  const wire = { status: 200, etag: '"opaque"', checksum: digest };
  const observed = { partNumber: 1, size: 3, sha256: digest, etag: '"opaque"' };
  verifyPartEvidence(part, wire, [observed]);
  for (const change of [{ etag: 'opaque' }, { sha256: 'different' }, { size: 4 }, { partNumber: 2 }]) {
    assert.throws(() => verifyPartEvidence(part, wire, [{ ...observed, ...change }]));
  }
  assert.throws(() => verifyPartEvidence(part, wire, []));
  assert.throws(() => verifyPartEvidence(part, wire, [observed, observed]));
  assert.throws(() => verifyPartEvidence(part, { ...wire, status: 403 }, [observed]));
  assert.throws(() => verifyPartEvidence(part, { ...wire, checksum: null }, [observed]));
  assert.throws(() => verifyPartEvidence(part, { ...wire, etag: null }, [observed]));
});

test('canonical SHA-256 and server geometry use exact final-part lengths', async () => {
  assert.equal(await sha256(bytes), digest);
  const file = new Blob([bytes]);
  assert.deepEqual(await expectations(file, { declaredSize: 3, partSize: 2, partCount: 2 }), [
    { partNumber: 1, length: 2, sha256: await sha256(new TextEncoder().encode('ab')) },
    { partNumber: 2, length: 1, sha256: await sha256(new TextEncoder().encode('c')) }
  ]);
  await assert.rejects(expectations(file, { declaredSize: 4 }));
});

test('browser sets checksum only; fixed body supplies forbidden signed headers', () => {
  assert.deepEqual(browserHeaders(signed(), bytes, origin), { 'x-amz-checksum-sha256': digest });
  for (const change of [
    value => { value.url = value.url.replace('https:', 'http:'); },
    value => { value.requiredHeaders.host = ['other.example.test']; },
    value => { value.requiredHeaders['content-length'] = ['4']; },
    value => { value.requiredHeaders.authorization = ['private']; },
    value => { value.requiredHeaders['x-amz-checksum-sha256'] = ['bad']; },
    value => { value.requiredHeaders.host.push('another'); }
  ]) {
    const value = signed(); change(value);
    assert.throws(() => browserHeaders(value, bytes, origin));
  }
  assert.throws(() => browserHeaders(signed(), bytes, 'https://different.test'));
});

test('direct transfer omits VaultDrive credentials and returns readable receipt headers', async () => {
  let count = 0;
  const result = await uploadPart(signed(), bytes, origin, { fetcher: async (url, options) => {
    count++;
    assert.equal(url, signed().url);
    assert.equal(options.body, bytes);
    assert.equal(options.method, 'PUT');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.redirect, 'error');
    assert.equal(options.mode, 'cors');
    assert.equal(options.referrerPolicy, 'no-referrer');
    assert.deepEqual(options.headers, { 'x-amz-checksum-sha256': digest });
    return new Response('', { headers: { ETag: 'opaque', 'x-amz-checksum-sha256': digest } });
  } });
  assert.equal(count, 1);
  assert.deepEqual(result, { status: 200, etag: 'opaque', checksum: digest });
});

test('wrong bytes fail locally and uncertain network dispatch is never retried', async () => {
  let count = 0;
  const fetcher = async () => { count++; throw new TypeError('uncertain'); };
  await assert.rejects(uploadPart(signed(), new Uint8Array(3).buffer, origin, { fetcher }));
  assert.equal(count, 0);
  await assert.rejects(uploadPart(signed(), bytes, origin, { fetcher }));
  assert.equal(count, 1);
});

test('negative probes tamper signature/checksum without broadening valid capability', async () => {
  for (const tamper of ['signature', 'checksum', 'missing-checksum']) {
    await uploadPart(signed(), bytes, origin, { tamper, fetcher: async (url, options) => {
      assert.equal(url === signed().url, tamper !== 'signature');
      assert.equal(options.headers['x-amz-checksum-sha256'] === digest, tamper === 'signature');
      if (tamper === 'missing-checksum') assert.deepEqual(options.headers, {});
      return new Response('', { status: 403 });
    } });
  }
});

test('bad-body and signed-length probes preserve registered checksum but change only dispatched body', async () => {
  for (const tamper of ['body', 'length']) {
    await uploadPart(signed(), bytes, origin, { tamper, fetcher: async (url, options) => {
      assert.equal(url, signed().url);
      assert.deepEqual(options.headers, { 'x-amz-checksum-sha256': digest });
      assert.notEqual(await sha256(options.body), digest);
      assert.equal(options.body.byteLength, tamper === 'body' ? 3 : 4);
      assert.equal(await sha256(bytes), digest);
      return new Response('', { status: 400 });
    } });
  }
});

test('authoritative resume pages advance, terminate and detect mismatched parts', async () => {
  const pages = [
    { parts: [{ partNumber: 1, size: 3, sha256: digest, etag: 'opaque' }], nextMarker: 1 },
    { parts: [], nextMarker: null }
  ];
  const paths = [];
  const observed = await listAll({ api: async path => { paths.push(path); return pages.shift(); } }, 'id');
  assert.equal(paths.length, 2);
  assert.match(paths[1], /marker=1&limit=1$/);
  const expected = [{ partNumber: 1, length: 3, sha256: digest }, { partNumber: 2, length: 1, sha256: digest }];
  assert.deepEqual(missingParts(expected, observed), [expected[1]]);
  assert.throws(() => missingParts(expected, [...observed, ...observed]));
  assert.throws(() => missingParts(expected, [{ ...observed[0], sha256: 'changed' }]));
  assert.throws(() => missingParts(expected, [{ ...observed[0], size: 4 }]));
  await assert.rejects(listAll({ api: async () => ({ parts: [], nextMarker: 0 }) }, 'id'));
});

test('login requires custom client header and stores only synchronizer secret', async () => {
  const store = storage();
  const client = new AuthClient({ storage: store, locks, fetcher: async (url, options) => {
    assert.equal(url, '/api/v1/auth/login');
    assert.equal(options.headers['X-VaultDrive-Client'], '1');
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.headers.Authorization, undefined);
    assert.deepEqual(JSON.parse(options.body), { email: 'fixture@example.test', password: 'password' });
    return credentials();
  } });
  await client.login('fixture@example.test', 'password');
  assert.equal(client.access, 'access');
  assert.equal(store.getItem('vaultdrive-harness-csrf'), 'csrf');
  assert.equal(store.getItem('accessToken'), undefined);
});

test('refresh burst shares one flight and never sends Authorization or refresh JSON', async () => {
  let count = 0;
  const client = auth(async (url, options) => {
    count++;
    assert.equal(url, '/api/v1/auth/refresh');
    assert.deepEqual(options.headers, { 'X-VaultDrive-CSRF': 'csrf' });
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.body, undefined);
    return credentials();
  });
  client.csrf = 'csrf'; client.access = 'expired-access';
  await Promise.all([client.refresh(), client.refresh(), client.refresh()]);
  assert.equal(count, 1);
  await client.refresh();
  assert.equal(count, 2);
});

test('separate clients use shared origin lock to serialize refresh network calls', async () => {
  let tail = Promise.resolve(), active = 0, maximum = 0;
  const sharedLocks = { request: (_name, action) => {
    const result = tail.then(action);
    tail = result.catch(() => {});
    return result;
  } };
  const fetcher = async () => {
    maximum = Math.max(maximum, ++active);
    await new Promise(resolve => setImmediate(resolve));
    active--;
    return credentials();
  };
  const clients = [1, 2].map(() => {
    const client = new AuthClient({ fetcher, storage: storage(), locks: sharedLocks });
    client.csrf = 'csrf'; return client;
  });
  await Promise.all(clients.map(client => client.refresh()));
  assert.equal(maximum, 1);
});

test('lost refresh response discards credentials and requires reauthentication without retry', async () => {
  let count = 0;
  const client = auth(async () => { count++; throw new TypeError('response lost'); });
  client.csrf = 'csrf'; client.access = 'access';
  await assert.rejects(client.refresh());
  assert.equal(client.access, null);
  assert.equal(client.csrf, null);
  await assert.rejects(client.refresh());
  assert.equal(count, 1);
});

test('API mutation does not refresh/retry after 401 or 503', async () => {
  for (const status of [401, 503]) {
    let count = 0;
    const client = auth(async (_url, options) => {
      count++;
      assert.equal(options.headers.Authorization, 'Bearer access');
      return new Response('', { status });
    });
    client.access = 'access';
    await assert.rejects(client.api('/api/v1/upload-sessions/id/complete', { method: 'POST' }),
      error => error instanceof HttpFailure && error.status === status);
    assert.equal(count, 1);
  }
});

test('logout carries bearer and CSRF, clearing state only on observed success', async () => {
  const client = auth(async (_url, options) => {
    assert.equal(options.headers.Authorization, 'Bearer access');
    assert.equal(options.headers['X-VaultDrive-CSRF'], 'csrf');
    return new Response(null, { status: 204 });
  });
  client.access = 'access'; client.csrf = 'csrf';
  await client.logout();
  assert.equal(client.access, null);
});
