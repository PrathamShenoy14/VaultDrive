# Browser multipart interoperability

Frontend M3 now provides the product picker/drop, queue, exact part-evidence
checks, resume, explicit recovery and native downloads. Follow
[its continuation steps and evidence boundaries](frontend-m3-transfers.md#reproduction-and-remaining-gates)
alongside this harness matrix. The existing HTTP Garage binding and denied
preflight still block actual browser multipart. Mocked frontend/Chrome passes
and real Garage-backed native downloads do not establish browser PUT interoperability.

The [API freeze](api-contract-freeze.md) and [Next.js integration guide](frontend-integration.md)
now provide the complete backend handoff and [OpenAPI artifact](openapi.json).
They preserve this document's same-origin API versus Garage PUT CORS distinction.
The real-browser HTTPS/CORS/clock gate below remains blocked.

Operational extension: [MVP hardening](operational-hardening.md) now provides an
independently opt-in bounded expiry scheduler and admission/429/retry contract.
The harness's original no-scheduler boundary below remains historical. For a
separate reviewed operational check, expiry may use the scheduler instead of
explicit internal pages; it remains database-only and never dispatches Abort.
No browser scenario or HTTPS/CORS/clock gate is closed by backend hardening.

Implemented: a bounded, dependency-free browser harness, local HTTPS/API proxy,
exact-origin Garage CORS template and executable protocol/transport checks.
**Live browser interoperability remains blocked.** The 2026-10-23 recheck found
no connected browser, HTTP-only Garage and a denied CORS preflight. See the
[current evidence matrix and executable continuation procedure](browser-multipart-verification-2026-10-23.md).
The earlier 2026-10-19 results below remain historical. Java/Node HTTP clients do not
establish browser cookie, CORS, forbidden-header or HTTP/2 behavior.

## Boundary and acceptance criteria

The harness exercises the existing API; it adds no production endpoint, migration,
storage identity, dispatch/recovery path, scheduler or frontend framework. The subsequent [backend consolidation](backend-consolidation.md) retires legacy
synchronous uploads; the harness already uses direct multipart. Existing hierarchy -> file -> allocation -> session
coordination and fresh Editor authorization remain authoritative. No Garage I/O
is moved into a transaction. Unknown Create/Complete/Abort outcomes must never
cause another storage dispatch. Explicit recovery uses the existing backend
protocol only. A ListParts page, expired URL or abort acknowledgement is neither
writer-termination evidence nor physical cleanup permission.

Acceptance requires actual browser results for every scenario below against the
deployed Garage endpoint, plus passing protocol tests and backend regressions.
Until then this is a harness/configuration deliverable, not browser release proof.
Do not use browser security overrides, disable checksums, relax cookie flags,
adjust signing time from an unauthenticated Date header or rewrite signed URLs.

## HTTPS and actual origins

Use the existing backend on loopback and Node's standard library HTTPS server:

```powershell
# From the repository root; use an already trusted localhost certificate.
$env:TLS_CERT='C:\private\localhost-cert.pem'
$env:TLS_KEY='C:\private\localhost-key.pem'
$env:HARNESS_ORIGIN='https://localhost:9443'
$env:API_UPSTREAM='http://127.0.0.1:8080'
$env:GARAGE_ORIGIN='https://garage.example.test'
node tools/browser-multipart/serve.mjs
```

Node 20.11.0 was used for repository checks. Open **https://localhost:9443/** in a
real browser. The server binds only
127.0.0.1, serves an explicit asset allowlist and proxies `/api/v1/` exactly once
to a fixed loopback backend. It preserves Set-Cookie/CSRF/download headers, has no
access/body logging, rejects foreign Host/Origin values, uses no-store and a CSP
with only the configured Garage connection origin. No package installation or new
deployed infrastructure is required. Backend startup/configuration is unchanged.
Use a separate backend instance and dedicated test database/schema with disposable
accounts and files, all production worker/consumer/publisher flags disabled. Never
point the harness at an unreviewed shared production backend.

Certificates must already be trusted by the browser, with valid hostname/expiry;
do not bypass certificate warnings. Keep private keys outside the repository.
Garage also needs a browser-reachable trusted HTTPS S3 endpoint. The configured
`storage.s3.endpoint` must already be that exact endpoint, reachable by both backend
and browser. Its authority/path and signed Host must survive routing/proxying
unchanged, including non-default ports. Do not replace localhost with a VM address
or HTTP with HTTPS in a returned URL. Existing retained allocation endpoint/bucket/
region/namespace identity is immutable: use fresh sessions with the approved binding
and preserve historical bindings; do not migrate identities for this test.

The supported browser API contract remains same origin. This harness makes the
API and page share an HTTPS origin, so login/refresh/logout need no backend CORS
or `Access-Control-Expose-Headers` additions. Secure HttpOnly SameSite=Strict
host-only refresh cookies remain fixed. Ports are not cookie isolation boundaries:
use a dedicated browser profile, and do not run another localhost app using the
same refresh-cookie name. Production should serve app/API under one HTTPS origin.
Cross-site auth and a standalone frontend-origin API CORS policy are out of scope.

## Garage CORS configuration (prepared, not applied)

Review [the CORS template](../tools/browser-multipart/garage-cors.example.json).
Its origin is the **actual page origin** `https://localhost:9443`, not Garage's
origin, an HTTP origin, or a wildcard. Change it if using another approved page
origin. The rule allows only PUT with `x-amz-checksum-sha256`, exposing `ETag` and
`x-amz-checksum-sha256`; OPTIONS is the CORS preflight, not an AllowedMethods entry.
No Garage cookies/JWTs, GET capability, DELETE, POST, wildcard request headers or
credentialed CORS are required. Content-Length and Host are generated by the browser;
they are not script-controlled preflight headers. The fixed ArrayBuffer body has
no Content-Type header. If a proxy adds required headers, review its behavior rather
than blindly expanding this rule.

With explicit configuration permission, an operator should first read/back up the
existing bucket CORS using GetBucketCors, merge this exact-origin rule, and apply
PutBucketCors through the already configured administrative S3 tooling. PutBucketCors
replaces the whole policy; never overwrite unrelated approved origins. Read it back,
then inspect a real OPTIONS and PUT from the actual browser origin. Do not grant
the browser storage credentials or configure CORS automatically on application startup.
This task applies no VM, Docker, TLS, clock or bucket configuration change.

Garage documents [bucket CORS support](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/).
Browser [forbidden request headers](https://developer.mozilla.org/en-US/docs/Glossary/Forbidden_request_header)
include Host and Content-Length; [CORS response exposure](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS)
is required to read ETag/checksum. These references explain the template; only live
execution can establish this deployment's behavior.

## Browser procedure and expected observations

Use synthetic accounts, an owner and a separate Editor profile. Do not export HARs
or screenshots containing credentials, cookies, signed URLs or login bodies. The
result export deliberately includes only origin, browser identity, timestamps and
scenario outcomes. It never exports tokens, URLs, email, filenames or provider bodies.
Keep DevTools header details private while checking cookie flags/framing.

| Scenario | Procedure / expected evidence |
|---|---|
| Authentication | Check missing client header -> 403. Log in with `X-VaultDrive-Client: 1`; bearer profile succeeds. Inspect Secure/HttpOnly/Strict/Path=/ cookie, no Domain, and CSRF response header privately. Cookie-only profile -> 401. |
| Refresh and CSRF | Check missing/wrong CSRF -> 403, then valid refresh succeeds. Refresh sends cookie + CSRF and **no Authorization**. Three concurrent harness callers produce one network refresh and a usable profile; cookie rotation proves browser acceptance. Log out -> old JWT 401 and cookie cleared. |
| Reserve/initiate | Use generated three-part fixture (8 MiB, 8 MiB, 37 bytes), fresh idempotency key and accessible folder. Reservation -> 201 RESERVED with correct initiator/owner/geometry; initiate -> 200 OPEN. A 202/transport failure requires read/recovery, never another uncertain Create. |
| Direct signed PUT | Upload first missing part. Network request goes to Garage, with exact immutable checksum and browser-generated Host/length; no API bearer/cookie. Expect 200, readable nonempty ETag and matching checksum. The harness compares exact opaque ETag (including quotes), length and SHA-256 with backend ListParts. Missing/different evidence fails. |
| Identical retry | After uploading part 1, Replay identical first part twice sends the same fresh URL and bytes twice. Both responses must be 200 and match the prior/backend ETag, size and digest. No control-plane retry is performed. |
| Resume | List uses limit=1 through every marker. Reload page, refresh cookie once, retain session UUID, reselect exact original file (or regenerate fixture), list and resume. Already uploaded size/digest/ETag must match; only missing registered bytes are sent. Reload alone does not resend a control-plane mutation. |
| URL refresh | Refresh first part URL; registration UUID, length and digest stay fixed. Upload/resume with fresh URLs succeeds. URLs are memory-only and absent from logs/export. |
| Invalid capabilities | On an OPEN disposable session, check altered signature, changed/missing checksum header, changed same-length bytes and wrong body length: HTTP 400/403. The last two preserve the registered signed checksum. Network/CORS failure is FAIL/unknown, never evidence of signature enforcement. Subsequent valid upload succeeds. |
| URL expiry | Refresh first part URL, then Validate retained URL before expiry: require 200 and exact backend evidence for that same URL. Keep the session OPEN; wait past its displayed deadline plus measured clock tolerance (at least the harness's five-second minimum) without replacing it. Try retained URL -> 400 or 403; inspect the provider expiry error privately to distinguish expiry from another rejection. Garage M0 observed 400. Refresh URL and validate again -> 200. No test-only re-signing/clock override counts as browser expiry evidence. |
| Completion | Resume all parts, Complete once -> completion disposition READY. A 202/lost response is pending/unknown; read then explicitly Recover completion. The UI blocks a second same-tab dispatch; backend durable guards remain authoritative across reloads. |
| Download | Download and compare -> authenticated attachment with exact original size and whole-file SHA-256. Bytes stream through the backend; browser receives no presigned GET. |
| Cancellation | In a separate RESERVED session, Cancel once -> CANCELLED without Create. In a separate OPEN session (optionally with an uploaded part), Cancel once -> CANCELLED or explicitly pending. After acknowledgement, initiate/sign -> 409. Read cancellation retains intent; no assertion of reclaimed bytes. Never repeat uncertain Abort. |
| Session expiry | Reserve a separate session and keep it past its immutable 24-hour PostgreSQL deadline (refresh login separately). Check expired session: read succeeds, new RESERVED initiation/sign/list -> 409. An already OPEN initiate replay can return its retained snapshot without new dispatch. No browser/admin expiry endpoint or shorter production TTL is added. |
| Expiry processing | After the deadline, a trusted internal caller may explicitly invoke existing `MultipartCancellationService.expire(after, limit)` in bounded pages; it records EXPIRED abandonment/name release without Garage I/O. Then browser read/cancellation can observe it. This is a separate operational check; do not change timestamps or add a scheduler/API for the harness. |
| Editor upload | Owner supplies an editable shared folder. Editor reserves there; the harness displays delegated=true by comparing ownerId with the current user (there is no delegated API field). initiatorUserId is Editor and ownerId is destination owner. Complete and compare download as Editor, then download as owner using the file UUID from session read/API. No root fallback. |
| Revocation | On a separate Editor session, refresh first part URL while OPEN; optionally complete/download before revocation to prove a formerly READY download becomes denied. Then owner revokes its only effective grant using a separate profile. Keep the destination folder field and retained capability. Check revoked admissions -> uniform 404 for read/sign/list/initiate/complete/cancel, new reservation into that folder and authenticated download. Existing presigned part URLs can still be used until expiry; revocation cannot recall them. Owner retains session recovery/cancellation authority. |

Refresh uses one tab-local promise plus origin-wide Web Locks. The CSRF secret is
kept in tab sessionStorage; access JWT and signed URLs are memory-only. API calls
never automatically refresh/retry on 401/503. On refresh network/parse/error failure,
credentials are forgotten and the user logs in again: an unobserved rotation may
have consumed the old token. Do not run independent refresh implementations in
other tabs of this profile; they must share the same serialization contract.
The harness supports browsers with Web Crypto, fetch and Web Locks in a secure
context. No browser portability or transparent browser-network retry claim is made.

Each action is explicit. Resume rechecks immutable registered hashes and authoritative
parts. A network/CORS error is not proof that storage did nothing; retain the session
and inspect it. The harness performs no automatic cleanup, sweeps, storage HEAD,
Create, Complete, Abort or Delete outside backend APIs. Never clean an uncertain
allocation based on age, empty listing or apparent URL expiry. Dispose of known
test resources only through existing authorized lifecycle operations.

## Clock and deployment gates

Synchronize browser OS, backend JVM, PostgreSQL host and Garage host with bounded,
monitored skew before interpreting URL deadlines. Record UTC times and signature
expiry in sanitized evidence. An HTTP Date probe is diagnostic only; it cannot
control production signing. Earlier M0 skew remains historical evidence, not a
current deployment bound. A single aligned sample does not establish ongoing sync.

On 2026-10-19, read-only SSH confirmed `dxflrs/garage:v2.3.0`, PostgreSQL 17 and
RabbitMQ 4.3.6 containers running. The configured S3 endpoint was
`http://localhost:3900` (bucket `vaultdrive-files`), not a trusted HTTPS endpoint.
An OPTIONS probe for `https://localhost:9443`, PUT and `x-amz-checksum-sha256`
returned **403**, wildcard Allow-Origin but **no Allow-Methods, Allow-Headers or
Expose-Headers**. Garage Date and local UTC agreed within approximately one second
at 18:10:26Z. This does not prove long-term skew bounds or browser expiry.

The available browser inventory returned `apps: [], browsers: []`. Consequently
**none of the browser scenarios above were executed**, including cookie rotation,
real-origin preflight, direct PUT framing, HTTP/2, native expired URL rejection,
Editor/revocation, cancel/24-hour expiry or download. The HTTPS certificate/endpoint
and approved bucket CORS also remain prerequisites. No VM configuration was changed.

## Repository verification

```powershell
node --test tools/browser-multipart/protocol.test.mjs tools/browser-multipart/serve.test.mjs
node --check tools/browser-multipart/app.mjs
```

The Node checks exercise protocol decisions with synthetic responses and real
loopback proxy HTTP. They do not automate a browser or demonstrate TLS trust.

Use the existing ignored environment in the Maven shell, an isolated verification
schema, the Garage version attestation and the four safe multipart gates from
[Sharing M6](sharing-m6.md#verification). Preserve separately gated process-kill,
M0 characterization and reviewed-journal checks. Keep all production execution
defaults disabled. The initial localhost AMQP listener was unavailable; a temporary
localhost-only SSH forward to the existing `vaultdrive_outbox_test` vhost was opened
for final verification. No VM configuration or default-vhost topology was changed.

Focused command from `backend/`:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=S3MultipartStorageTest,SecurityIntegrationTest,AuthControllerTest,AuthenticationSessionIntegrationTest,MultipartTransferIntegrationTest,MultipartCompletionIntegrationTest,MultipartCancellationIntegrationTest,DelegatedMultipartIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=browser_multipart_focused' '-Dspring.flyway.schemas=browser_multipart_focused' '-Dspring.flyway.default-schema=browser_multipart_focused' test
```

Final full command from `backend/`, with the existing isolated AMQP tunnel open:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
.\mvnw.cmd -o '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=browser_multipart_final_verification' '-Dspring.flyway.schemas=browser_multipart_final_verification' '-Dspring.flyway.default-schema=browser_multipart_final_verification' test
```

The first small backend attempt used the historical default test schema and hit
its existing V26 checksum mismatch (database 1493870529, repository -713442801).
No migration or shared schema was repaired. The isolated focused run passed
**214 tests with no failures/errors/skips**. The first full attempt finished with
eight RabbitMQ connection-refused errors before the tunnel was available; all
other tests passed, with 21 separately gated skips. The complete rerun result is
recorded below. Node final checks passed **17 tests**,
including checksum/header restrictions, invalid-body probes, refresh serialization,
uncertain-response no-retry behavior, cookie/header proxying and asset isolation.
JavaScript syntax, CORS JSON, control bindings, local Markdown targets and
`git diff --check` also passed.

Final full run completed at 2026-10-19 18:25:27 UTC: **1,386 tests; 1,365 passed,
21 skipped; zero failures/errors**. This includes all 12 isolated RabbitMQ checks
and 27 gated Garage multipart/evidence checks. Default contexts used
`browser_multipart_final_verification`; suites with their own isolation retained
their existing private-schema patterns. Test-owned mutable fixtures, storage and
broker topology followed existing suite cleanup; synthetic verification schemas
remain retained. No historical ambiguous allocation/journal was adopted or swept.

| Separately gated suite | Skipped | Prerequisite not enabled |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` and isolated end-to-end runbook |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` and M0 characterization runbook |
| `GarageMultipartJournalRecoveryTest` | 1 | separately reviewed retained-run UUID |

These backend/Node results do not close any of the live browser blockers above.
The temporary task-owned AMQP tunnel was closed after verification. No production
processing default, existing migration, VM clock or bucket policy was changed.
