# Multipart completion and explicit recovery — Phase 3E / M3

M3 extends [M1 identity](multipart-upload-sessions.md) and
[M2A part transfers](multipart-part-transfers.md) with frozen completion,
multipart-specific evidence and READY. It preserves the
[M0 safety boundary](garage-multipart-capabilities.md): immutable signed exact
length/SHA-256 for every part, one trusted backend control plane, permanent
allocation identity, no uncertain redispatch, and no inference of writer
termination from expiry or provider absence. Synchronous uploads are unchanged.

M3 itself introduced no cancellation/abort API, presigned completion/abort,
autonomous recovery, expiry/cleanup, worker, browser UI or new infrastructure.
[M4 adds cancellation and explicit expiry](multipart-cancellation-expiry.md),
preserving every permanent completion decision and recovery constraint.

## API and durable state

All requests use the existing owner JWT under `/api/v1/upload-sessions`:

| Request | Contract |
|---|---|
| `POST /{id}/complete` | No body. Validate authoritative parts, freeze one server-selected manifest/decision, acquire one durable dispatch permission, complete, record evidence, attempt READY. |
| `POST /{id}/recover` | No body. Resume an undispatched frozen decision if freshly eligible; otherwise inspect one exact completed object, or retry receipt-backed metadata finalization. Never redispatch an existing dispatch identity. |
| `GET /{id}` | Retained session plus nullable `completion:{disposition,revision,dispatchId,receiptRecorded,reason}`. |

Complete/recover return 200 only when `completion.disposition` is READY; otherwise
202 with Location for durably chosen pending/unknown/blocked completion. Missing
expectations, invalid manifests, abandonment, unopened sessions and incompatible
decisions return 409. Provider listing failure before a decision returns sanitized
503; it does not silently choose completion. Existing 400/401/404 conventions remain.

The V27 session state stays `COMPLETION_DECIDED` permanently, including after
READY. Its decision UUID and revision never change afterward. Completion progress
has its own monotonic revision, incremented exactly once for an actual change.
Duplicate requests retain the first manifest, dispatch ID and receipt timestamp.

Public requests first perform owner-scoped lookup and fresh access checks. Unknown,
foreign and inaccessible sessions have the same non-leaking 404. An already
authorized operation racing folder trash can return 202 BLOCKED and retain its
observed facts. A new request while that folder is inaccessible still returns 404.
After READY, session reads follow the current authorized file location, including
ordinary rename/move; Trash or pending purge blocks public access as usual.

## Manifest and dispatch protocol

1. In a short transaction, lock shared hierarchy → fresh file → allocation →
   session and validate the original upload identity, ancestry, expiry and current
   storage binding. Require every fixed-geometry part to be registered.
2. Outside the transaction, use authoritative Garage ListParts in pages of at most
   100. At most seven pages and 640 parts are accepted. Require exactly ordered
   numbers 1 through partCount, exact registered lengths/SHA-256, fixed geometry,
   bounded nonblank opaque ETags, and consistent increasing pagination. Missing,
   extra, duplicate, unordered, mismatched or incomplete metadata fails closed.
3. Re-lock and revalidate. Atomically choose V27 completion over abandonment and
   insert the exact ordered manifest, including registration UUIDs, lengths,
   digests and opaque ETags. PostgreSQL validates it against immutable part rows,
   computes a SHA-256 hash of canonical PostgreSQL JSONB text, and computes the
   expected Garage composite checksum. The manifest cannot subsequently change.
4. In a separate short transaction, assign one immutable dispatch UUID and
   database permission timestamp, changing progress to UNKNOWN before Garage I/O.
   Only the transaction creating that UUID returns an ephemeral permission.
   Death, timeouts and restarts never make it reassignable.
5. Outside transactions, call CompleteMultipartUpload once with the exact retained
   bucket/key/upload ID, frozen ETags/part checksums, SHA256 composite checksum and
   COMPOSITE type. The production SDK has one attempt and finite call timeouts.
6. Validate its checksum/type/opaque ETag, then independently HEAD that exact key
   with checksum mode enabled. Commit positive multipart evidence before a
   separate freshly eligible status/version CAS to READY.

Once the completion decision commits, M2A cannot issue or refresh part URLs.
Already issued capabilities remain reusable for the same immutable bytes. ListParts
does not reveal every accepted streaming handler, and it cannot establish request
quiescence. Their remaining work is safe for this supported protocol because it
cannot authorize different content/length. A selected ETag that becomes unusable
can leave completion unknown; no retry or weaker manifest is substituted. M0's
arbitrary different-content race remains unsupported.

## Garage checksum and positive recovery evidence

The pinned [Garage 2.3.0 implementation](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/multipart.rs)
and the new live two-part gate establish this checksum format:

```text
Base64(SHA256(binarySHA256(part1) || ... || binarySHA256(partN)))
```

Garage returns this raw 44-character Base64 value without an AWS-style `-N`
suffix. It is a **composite checksum, not SHA-256 of the concatenated file bytes**.
ETags remain opaque; M3 does not derive checksums from them. The live gate checks
the request checksum, Complete response, HEAD response and independently downloaded
exact bytes. HEAD's checksum-type field is not required: the known initiation
algorithm and retained manifest determine the verified format.

An uncertain dispatch can be adopted only when all of these conditions hold:

- A retained DIRECT_MULTIPART allocation/session has exact original full binding
  and key, a durable exact Garage upload ID, and matching M2A initiation dispatch
  identity. There is one immutable completion decision, frozen validated manifest
  and nonnull completion dispatch identity.
- The original configuration still matches; stored endpoints never construct
  arbitrary clients. The exact-key checksum-enabled HEAD succeeds with exact
  declared size/type and exact frozen-manifest composite SHA-256.
- Backend-set `vaultdrive-session` and `vaultdrive-initiation` metadata exactly
  match the retained UUIDs. The returned opaque ETag is valid and bounded. For
  the known-acknowledgement path, it must also equal the Complete response ETag.
- The accepted trusted single-control-plane assumption holds: keys are never
  reused, no alternate PUT/Copy/Create/Complete writer can overwrite/adopt this
  allocation, clients possess only checksum/length-bound UploadPart capabilities,
  and incompatible older deployments cannot process these allocations.

The metadata/checksum plus those permanent identities and sole completion manifest
bind the observation to this exact upload. HEAD itself does not expose the upload
ID or manifest hash; it supplies no version-specific deletion fence. Missing or
mismatched evidence, HEAD errors, NoSuchUpload, object age and process death do not
prove failure, absence of parts, termination or cleanup eligibility. No bounded
age eventually converts an unknown dispatch into another permission.

## V29 receipt and transaction guards

[V29](../backend/src/main/resources/db/migration/V29__multipart_completion_evidence.sql)
adds only `multipart_completions` and `multipart_completion_receipts`.

The completion row retains its immutable session/decision/upload ID, ordered
manifest/hash/checksum and creation time. Dispatch identity/time are single-assignment;
disposition/reason/revision are guarded mutable progress. Dispositions are PENDING,
UNKNOWN, STORAGE_COMPLETE, BLOCKED and terminal READY. There is no lease or worker.

The immutable receipt copies exact allocation/file/session identity, full storage
binding/key, initiation/upload/decision/dispatch identities, manifest hash, observed
size, verified composite SHA-256, opaque ETag, content type and metadata, evidence
kind (`COMPLETE_HEAD` or `HEAD_RECOVERY`) and database timestamp. Its insertion guard
binds every field to retained identity. UPDATE/DELETE are forbidden.

Receipt recording takes hierarchy → file → allocation → session → completion →
receipt coordination but deliberately does not require accessible ancestry or an
unexpired authorization: it records an observed fact after a previously permitted
call. READY separately rechecks original UPLOADING status/version/destination,
bounded accessible ancestry, no purge/cleanup evidence and the configured binding.
The status/version CAS and completion READY progress commit together. A database
file-row guard requires the protocol-specific receipt on every multipart READY
path, including legacy JPA/native writes.

V25 admission/PUT-success fields remain forbidden for multipart, and V26 gains no
cleanup authority. All multipart file deletion, legacy purge target/work and
folder-led byte-purge fences remain. **Even a receipt-backed multipart READY file
still blocks physical purge** until a separate multipart removal proof is designed.
Folder purge snapshots are never rewritten by completion recovery.

## Explicit recovery and crash boundaries

| Durable boundary | Safe outcome / next explicit action |
|---|---|
| No decision | Retry authoritative validation; no completion has been permitted. |
| Frozen PENDING, no dispatch ID | Complete/recover may claim once after fresh identity/ancestry/expiry checks. |
| Permission committed, process dies before sending | UNKNOWN forever unless exact positive completed-object evidence appears; never dispatch again. |
| Complete timeout, NoSuchUpload or lost response | UNKNOWN; duplicate complete returns retained state. Recover may perform one exact HEAD. |
| Bad acknowledgement or mismatched positive HEAD | BLOCKED without receipt; an explicit later exact observation may recover. |
| Complete/HEAD succeed, receipt commit fails | Retain bytes and UNKNOWN; exact positive recovery may independently record the receipt. |
| Receipt commits, READY fails or destination is trashed/purged | Receipt retained; BLOCKED or last acknowledged storage disposition. Recovery retries metadata only. |
| READY response is lost | Duplicate complete/recover returns READY without storage calls. |

Each recovery request is bounded: no scan, retry loop, polling delay or scheduler.
With a receipt, it performs no Garage I/O. With an existing dispatch and no receipt,
it performs at most one checksum-enabled HEAD, never ListParts or Complete. A
frozen undispatched decision can still dispatch once while eligible. Expiry blocks
new dispatch permission, but does not invalidate already observed success or its
receipt-backed metadata finalization. Configuration drift blocks I/O/READY without
substituting the current configuration for retained identity.

Old M1 completion decisions without an M3 frozen manifest remain retained and
blocked (202, no new dispatch authority). Unknown initiation remains an M2A gap;
M3 does not infer/reconstruct its missing Garage upload ID.

## Verification and limits

[Database/API tests](../backend/src/test/java/com/vaultdrive/file/MultipartCompletionIntegrationTest.java)
use fresh UUID PostgreSQL schemas, committed fixtures, independent transactions,
blocking-pid observations and bounded latches. They cover permanent decision and
dispatch races, frozen identity/receipt guards, concurrent recovery, invalid
manifests, every committed crash boundary, receipt/READY failures, expiry,
configuration mismatch, folder barriers and real JWT status/access behavior.

[Live tests](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartCompletionIntegrationTest.java)
use real sessions, production adapters, presigned part transfers, exact downloaded
bytes and backend metadata, real provider pagination, a response-drop TCP proxy,
an accepted identical part held across completion, and readable unrelated controls.
The test journal identifies every new disposable key/upload ID before network
dispatch. Cleanup uses only known complete objects or test-owned, directly proven
never-completed open uploads after all HTTP tasks join; it never derives authority
from absence. The retained ambiguous M0 allocation and old journals are untouched.

Run with the ignored local environment loaded and read-only Garage 2.3.0 attested:

```powershell
$env:GARAGE_MULTIPART_COMPLETION_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o '-Dtest=MultipartCompletionIntegrationTest,GarageMultipartCompletionEvidenceTest,GarageMultipartCompletionIntegrationTest,S3MultipartStorageTest' test
```

Crash/restart coverage simulates committed application boundaries and reconstructs
the service over retained PostgreSQL state; it does not kill an OS process. No
multi-node, replication, GC, production load, browser, HTTP/2 or physical part
reclamation guarantee is established. M0's clock-skew/TLS/CORS prerequisites still
gate browser release. No cancellation or cleanup follows from a receipt or READY.

Executed on 2026-10-18:

- Combined focused selection: **297 passed**, zero failures/errors/skips: 50 M3
  PostgreSQL/API cases, ten live M3 cases, one checksum gate, eleven adapter unit
  cases, and 225 M1/M2A/legacy adapter/single-PUT/expiry/receipt/cleanup regressions.
- Full safely isolated backend suite: **1,054 entries, 1,034 passed, 20 skipped**,
  zero failures/errors. All seven live broker cases, continuous HTTP → outbox →
  RabbitMQ → job → Garage verification, folder purge and legacy upload regressions
  passed. Skips are the 18 separately opt-in M0 characterization cases, reviewed
  journal disposal and the process-recovery suite entry; no M0 journal was replayed.
- The live two-part gate matched raw composite checksums in Complete and HEAD
  against the ordered part hashes, exact downloaded bytes and retained metadata.
  Production multipart completion also produced the exact registered bytes through
  the existing authenticated download path.
- A one-request TCP proxy consumed Garage's real successful completion response
  and dropped it downstream. The backend retained UNKNOWN, issued no second
  Complete, and recovered READY only from exact positive HEAD evidence. Receipt
  persistence and READY-commit failures preserved the actual completed bytes.
- Concurrent completion used one dispatcher; positive recovery retained one
  immutable receipt. Real provider pages formed the ordered manifest. An accepted
  identical part returned after completion without changing completed bytes.
  Production Abort/Delete call counts stayed zero; test fixture controls stayed
  readable and owned storage cleanup retained no new ambiguous allocation.

Full verification used the disposable copy `backend/target/m3-full-36e337ff`, with
all production source/resource hashes matching the checked-out files. Only test
schema literals/default test profile were redirected to twelve fresh private names
recorded in its `schema-map.json`. Exactly those twelve schemas were removed
afterward, with zero remaining under the task prefix. The newly owned Garage
journals all reported `retained=0`; shared public, retained regression schemas,
unrelated objects and the retained ambiguous M0 allocation were never cleanup targets.

Live broker tests used the existing isolated `vaultdrive_outbox_test` vhost through
a task-owned local SSH tunnel, closed after verification. The continuous harness
used the documented `ASYNC_E2E_ALIGN_INITIAL_DUE=true` fixture option for the known
JVM/VM skew. This tests execution after initial eligibility, not production
scheduling under skew. No VM, Docker, clock, credentials, dependencies or autonomous
processing defaults changed.
