# Multipart part transfers — Phase 3D / M2A

M2A adds the backend control plane for initiation, immutable part authorization and
resume. It builds on [M1 identity/decision guards](multipart-upload-sessions.md)
and the [M0 verified Garage contract](garage-multipart-capabilities.md). Synchronous
uploads retain their existing protocol. At the M2A boundary, completion,
abort/cancellation, READY, browser clients, reconciliation and autonomous cleanup
were unimplemented.

This is the M2A milestone record. Phase 3E subsequently adds
[M3 safe completion, durable evidence, READY and explicit recovery](multipart-completion-recovery.md).
M3 preserves M2A's part authorization and initiation contracts.
[M4 adds cancellation/expiry](multipart-cancellation-expiry.md) and final response
authorization after signing. Autonomous cleanup and browser UI remain planned.

## Authenticated endpoints

All routes below are under `/api/v1/upload-sessions`. They use the existing bearer
JWT subject as owner. Missing/foreign/inaccessible sessions return the same 404;
missing/invalid JWT returns 401. Every existing-session operation checks fresh
file/allocation/ancestry state under the existing locks. There is no root fallback.

| Request | Body / query | Result |
|---|---|---|
| `POST /` | `Idempotency-Key` header; `{folderId?, name, size, contentType?}` | 201 plus Location; M1 reservation only. Identical owner/key/payload replays the retained identity/deadline; changed payload is 409. |
| `POST /{id}/initiate` | No body | 200 OPEN with durable exact upload ID, including duplicates; 202 INITIATING plus Location for pending/unknown outcomes. Incompatible decisions return 409. |
| `POST /{id}/part-urls` | `{parts:[{partNumber,length,sha256}]}` | 200 array of `{part:{partNumber,length,sha256,registrationId}, upload:{url,requiredHeaders,expiresAt}}`; `Cache-Control: no-store`. |
| `GET /{id}/parts` | `marker=0`, `limit=100` defaults | 200 `{parts:[{partNumber,size,etag,sha256}],nextMarker}`; null nextMarker ends pagination. |
| `GET /{id}` | No body | 200 durable session identity/state/revision, initiation/upload/decision IDs, timestamps, declaredSize, partSize and partCount. |

`sha256` is canonical standard Base64 of exactly 32 bytes, not hex. ETags are
opaque. The part URL response contains the signed `host`, `content-length` and
`x-amz-checksum-sha256` values. A direct uploader uses PUT to the exact URL, sends
the checksum, and supplies the exact length through HTTP framing. It sends no
VaultDrive JWT to Garage. Browsers must generate the matching Host/Content-Length;
actual browser portability remains outside M2A.

Validation failures return 400, immutable expectation/authorization conflicts 409,
and unavailable signing/listing outcomes 503 with sanitized messages. A changed
length or digest on an existing validly identified part conflicts, even if the
new length would violate geometry. A whole signing batch commits or rolls back
atomically; signing failure leaves committed expectations available for refresh.

## Bounds and immutable authorization

M1 still fixes positive declared size at at most five GiB, eight-MiB parts,
ceiling-divided part count (at most 640), and a smaller final part. Clients cannot
choose offsets or geometry. Part numbers uniquely identify disjoint ranges.
Signing batches contain 1–100 distinct parts. Listing pages contain 1–100 parts;
the marker is bounded by the session's part count. No endpoint scans all parts.

[V28](../backend/src/main/resources/db/migration/V28__multipart_transfer_authorization.sql)
adds two append-only tables, with no changes to applied migrations or existing
evidence:

- `multipart_initiation_dispatches`: one session/initiation identity and database
  permission-decision timestamp. UPDATE/DELETE are forbidden. This is not a lease
  or evidence that the request reached Garage.
- `upload_session_parts`: session/number primary key, exact length, canonical
  SHA-256, unique server-generated registration UUID and database timestamp.
  UPDATE/DELETE are forbidden, including no-op updates. PostgreSQL validates OPEN,
  eligibility, expiry and exact fixed geometry at insertion.

Identical registrations and URL refresh retain the original registration UUID,
length and digest. Valid changed expectations return 409. No caller can authorize
different bytes for an existing part: M0's different-content concurrent-write
failure is explicitly excluded. Same-content replay remains possible; capabilities
are not single-use tokens or temporary-storage quotas.

The nominal SDK signing lifetime is at most five minutes, reduced by time since
authorization and capped by both remaining database session lifetime and its
absolute deadline. The adapter rounds down with a margin and refuses a signed
expiry beyond the authorization deadline. Expired sessions issue no new URLs or
list requests. GET retains expired durable state. URL expiry does not terminate
an already accepted request or authorize cleanup.

**Clock limitation:** M0 measured substantial JVM/Garage skew, and Garage accepted
future-dated SDK signatures. Five minutes is the encoded signature lifetime, not
a verified five-minute wall-clock authorization bound in that environment. No
production clock override or unauthenticated Date-based signing is introduced.
Bounded clock skew, HTTPS, approved CORS and real-origin browser fetch remain
deployment prerequisites; M2A does not enable a browser release.

## Transactions, initiation and uncertainty

`MultipartSessionService` owns short PostgreSQL transactions, with statement/lock
timeouts and shared hierarchy → fresh file FOR UPDATE → allocation FOR UPDATE →
session FOR UPDATE → parts in ascending number order. Protected state is read
after locking. Folder structural mutations retain exclusive hierarchy locking.
Configured storage binding must exactly match the retained namespace, endpoint,
region and bucket; the stored values never construct an arbitrary storage client.

`MultipartTransferService` rejects ambient transactions. It commits the transition
to INITIATING, immutable initiation ID, allocation `write_started` and the V28
dispatch record before calling `ObjectStorageService`. Only the newly committed
claim returns an ephemeral dispatch permission. Replays of INITIATING, including
M1 sessions without a V28 dispatch record, grant no dispatch. Death after permission
and before actual I/O is deliberately conservative.

The Garage adapter uses the configured single-attempt SDK client, finite call
timeouts, SHA256 multipart checksums and backend-set session/initiation metadata.
The exact returned upload ID must commit independently before OPEN or any URL.
Conflicting identities are rejected. Any uncertain Create or result-binding failure
returns 202 INITIATING and never causes another Create, abort or DELETE. Even a
known returned ID can remain unbound if expiry/ancestry prevents the M1 transition.
There is no safe reconciliation endpoint in M2A; such sessions may remain blocked
indefinitely. Dispatch death tests simulate committed boundaries, not OS process
termination.

Registration commits before presigning. Signing and authoritative Garage ListParts
run outside database transactions and hierarchy locks. The adapter signs the exact
retained bucket/key/upload ID/part number and fails closed unless the SDK reports
signed exact Content-Length, SHA-256 and Host. Neither URLs nor provider exceptions
are persisted or logged by these paths; capability DTO string representations are
redacted. Operational request/response logging must also preserve that boundary.

Authorization that commits before folder trash can already have issued a reusable
capability. Trash/purge prevents subsequent authorization and ID-binding when it
wins first; it does not revoke existing URLs or fence accepted writers. ListParts
is a point-in-time provider page, excludes unfinished requests, and is never
deletion/termination authority. NoSuchUpload is reported as unavailable without
changing durable state. HEAD, object age and absent parts are unused. All M1
legacy-evidence, READY and purge fences remain in place.

## Verification

The database/API suite uses a new UUID schema, real JWT validation and PostgreSQL
blocking-pid observations to force competing registration/dispatch/folder orders.
The adapter unit suite verifies signing bounds, binding drift, error sanitization,
transaction rejection and provider pagination. The live suite uses real sessions,
the production Garage adapter and new journalled test allocations with readable
controls. Production Complete/Abort/Delete calls are forbidden by assertions;
test-only fixture cleanup aborts known open disposable MPUs after all requests
join. No M0 recovery journal or retained ambiguous allocation is adopted.

From `backend/`, load the ignored environment, attest Garage 2.3.0 read-only, then:

```powershell
$env:GARAGE_MULTIPART_TRANSFER_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o '-Dtest=MultipartTransferIntegrationTest,GarageMultipartTransferIntegrationTest,MultipartSessionIntegrationTest,S3MultipartStorageTest,S3ObjectStorageServiceTest,SinglePutDispatchIntegrationTest,StaleUploadExpiryIntegrationTest,UploadFinalizationRecoveryIntegrationTest,KnownSuccessCleanupIntegrationTest' test
```

The live expiry case re-signs the production-marshalled request using M0's test-only
Garage-aligned clock, without changing its checksum/length/identity restrictions.
Native production-presigner acceptance and refresh are verified separately; this
does not repair deployment skew. Actual browser, HTTP/2, multi-node, load, physical
reclamation and process-kill behavior are unverified.

Executed on 2026-10-18:

- Focused command above: **233 passed**, zero failures/errors/skips. This includes
  24 M2A PostgreSQL/API cases, eight adapter unit cases, four live PostgreSQL/Garage
  cases, all 36 M1 cases and 161 legacy adapter/single-PUT/expiry/receipt/cleanup cases.
- Full safely isolated backend suite: **990 entries, 970 passed, 20 skipped**, zero
  failures/errors. All seven live broker cases and the continuous HTTP → outbox →
  RabbitMQ → job → Garage scenario passed, including readable controls and cleanup.
  Skips are the 18 separately opt-in M0 characterization cases, the reviewed-journal
  disposal case and the process-recovery suite entry. No M0 journal was replayed.
- Live Garage rejected tampered bucket/key/upload ID/part number/signature and
  changed checksum headers with 403; wrong registered bytes returned InvalidDigest,
  changed body length returned 403, and missing checksum returned 400. Identical
  byte replay and refresh succeeded. Three parts paginated in three one-part pages.
- The provider-aligned expired signature was rejected; production-signed refresh
  succeeded. Concurrent changed-content submission was rejected while the original
  registered content succeeded. All production SDK Complete/Abort/Delete assertions
  remained zero. Known disposable uploads were cleaned with zero retained targets;
  unrelated controls remained readable throughout.
- Initial test runs corrected Mockito fixture restubbing/reset and a deferred-trigger
  fixture setup before the passing final focused run. They established no new
  ambiguous storage outcome and performed no old-journal recovery.

Full verification used the disposable copy `backend/target/m2a-full-1ec1dbab`.
All production source/resource hashes matched the checked-out files. Only fixed
test schema literals and the default test-profile schema were redirected; the
copy's `schema-map.json` records the twelve effective fresh names. Cleanup failed
closed on a manifest naming mismatch caused by overlapping test-name replacement;
the copied source and Flyway creation logs established the exact effective name
before correction. Exactly those twelve schemas were then removed. Shared public
and retained regression schemas were never targets. The M2A Garage fixture finished
with zero retained allocations, and the retained ambiguous M0 allocation was untouched.

The broker tests used the existing isolated `vaultdrive_outbox_test` vhost and
temporary local SSH forwarding, closed after verification. The continuous harness used the documented
`ASYNC_E2E_ALIGN_INITIAL_DUE=true` fixture option for the known clock skew. This
establishes execution after initial eligibility, not production scheduling under
skew. No VM, Docker, credentials, clock or processing defaults changed.
