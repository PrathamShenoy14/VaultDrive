# Direct upload and resumability architecture — Phase 3A

Design report preserved from the Phase 3A chat investigation. Reviewed repository
baseline: `5ea54e1`, including V26. This is a proposal, not implemented behavior.
“Verified repository behavior” means source/test inspection, not a new test run.
Garage findings are source-level expectations unless explicitly verified by the
separate [M0 capability investigation](garage-multipart-capabilities.md).

Phase 3C now implements only the [M1 database/session foundation](multipart-upload-sessions.md).
That contract supersedes the proposed M1 details below. Phase 3D now adds the
[M2A backend part-transfer contract](multipart-part-transfers.md), superseding
initiation/part/resume details below. Phase 3E adds the
[M3 completion/evidence/recovery contract](multipart-completion-recovery.md),
superseding the proposed completion details. [M4 cancellation and explicit expiry](multipart-cancellation-expiry.md)
now supersede the proposed abandonment details. Later removal proofs and browser
enablement remain planned.

## Decision

GO for a bounded Garage capability-verification slice; NO-GO for enabling direct
uploads until its safety gates pass. Use presigned **UploadPart** URLs, with
Spring Boot retaining exclusive authority to initiate, complete and abort uploads.
Keep the synchronous API during migration. Do not begin M1 automatically.

## Current architecture and migration constraints

| Boundary | Confirmed repository behavior |
|---|---|
| Reservation transaction | Shared owner hierarchy lock; validate ownership, destination ancestry and name; commit UPLOADING metadata and immutable storage allocation. |
| Admission transaction | Hierarchy → fresh eligible file → allocation; consume admission once and capture version/destination. |
| Storage | One synchronous PUT outside database transactions, SDK retries disabled. Input-stream closure must also succeed. |
| Success transaction | Independently commit immutable V25 PUT-success evidence. |
| READY transaction | Fresh hierarchy/file/allocation validation and status/version CAS. Inaccessible destinations remain blocked with retained bytes. |
| Failure/recovery | PUT/closure failure attempts FAILED. Receipt/finalization uncertainty retains bytes; recovery never repeats PUT. |

Sources: [FileService](../backend/src/main/java/com/vaultdrive/file/FileService.java),
[finalization](../backend/src/main/java/com/vaultdrive/file/UploadFinalizationTransactions.java),
[admission](../backend/src/main/java/com/vaultdrive/storage/FileStorageIdentityService.java),
[success recovery](upload-success-recovery.md),
[single-dispatch verification](single-put-dispatch-verification.md),
[V26 cleanup](known-success-upload-cleanup.md).

V26 additionally requires receipt-backed FAILED history, permanent abandonment,
exact retained identity and the trusted sole-writer proof. A multipart receipt
must not populate `put_succeeded_at` or acquire V26 cleanup authority. Keep both
APIs as separate protocols. Never switch an existing allocation between PUT and
multipart. A different upload gets a new file UUID/key. Deployment must exclude
older incompatible instances from processing multipart allocations.

## Control plane and data plane

| Component | Proposed responsibility |
|---|---|
| Spring Boot | JWT authentication, ownership/ancestry, durable decisions, presigning, completion/abort coordination and READY. |
| PostgreSQL | Allocation/session identity, expected content, irreversible decisions, completion evidence and recovery state. |
| Garage | Part bytes, multipart state and completed bytes. |
| Client | Chunking, checksums, direct requests, bounded concurrency, retries, progress and resume. |

Use multipart for small positive-length direct uploads too, avoiding a separate
presigned PUT proof. Initially retain the synchronous path for zero-byte files.

## Proposed API and authorization

Base path: `/api/v1/upload-sessions`.

| Request | Contract |
|---|---|
| POST / | Name, destination, declared size/type, Idempotency-Key. Atomically reserve metadata/allocation/session, initiate outside transaction; 201 when OPEN, 202 with location when unresolved. |
| POST /{id}/part-urls | Bounded part-number/SHA-256 batch; register immutable size/digest; return URLs, required headers and expiry. |
| Direct PUT | Only UploadPart using returned URL/headers; never send the VaultDrive JWT to Garage. |
| GET /{id}/parts?cursor=… | Backend paginates ListParts; return completed number, size, opaque ETag and available checksum. |
| POST /{id}/complete | Validate authoritative parts, freeze ordered manifest, durably choose completion, dispatch server-side; 200 READY or 202 unresolved/blocked. |
| GET /{id} | Durable state, file ID, expiry, readiness/blocking disposition. |
| POST /{id}/cancel | Durably choose abandonment; 202 while storage disposition is unresolved; preserve duplicate decisions. |
| POST /{id}/recover | Explicit reconciliation/eligible metadata retry, never blind completion retry. |
| Internal expiry | Explicit bounded pages; no scheduler or new job initially. |

All public control requests require JWT, owner-scoped lookup and fresh ancestor
access checks. Preserve 401 unauthenticated and non-leaking 404 foreign/inaccessible
responses; 409 for incompatible decisions or changed idempotency payloads.

Initial proposed policy, not measured limits: five-minute URLs capped by a fixed
24-hour session deadline, eight-MiB parts with smaller final part, five-GiB file
maximum, three parallel client requests and bounded signing batches. Validate
filename and bounded content-type metadata; MIME does not prove file format.
Require signed SHA-256 and exact length **where browser/Garage enforcement passes
M0**. Declared size and final checks alone cannot prevent oversized temporary
uploads. Short URLs impose no hard temporary-storage quota against replay.

URLs are bearer capabilities: HTTPS, no query-string logging or browser persistence,
and backend-only secret credentials. Browser reachability, an approved HTTPS
endpoint and CORS are prerequisites, not infrastructure changes authorized here.

## Session state machine

```text
RESERVED → INITIATING → OPEN
                  ↘ INITIATION_UNKNOWN
OPEN → COMPLETING → STORAGE_COMPLETE → READY
                ↘ COMPLETION_UNKNOWN
                ↘ BLOCKED
OPEN → ABORTING → CLOSED
              ↘ ABORT_UNKNOWN
```

CLOSED has cancellation/expiry reason. STORAGE_COMPLETE may remain ancestry-blocked
without losing success evidence. Commit admission before CreateMultipartUpload;
conservatively consume `write_started` so never-admitted expiry cannot misclassify
it. Issue no URL before the exact upload ID is durable. Completion and abandonment
are mutually exclusive decisions. Once completion may have been dispatched, no
cancel/expiry abort or DELETE is permitted.

Completion dispatch permission is non-reassignable: death, lease timeout or a
crash before sending leaves an unknown state, not a replacement dispatcher.
Duplicate HTTP completion observes the same decision. COMPLETION_UNKNOWN grants
neither READY nor deletion. Expiry stops authorization, not storage work. A
specifically verified pre-mutation rejection might later permit another attempt;
the first slice permits no general retry after uncertainty.

## Essential schema, adapter and transaction changes (proposed)

| Location | Essential concepts |
|---|---|
| Retained allocation | Immutable server-PUT/direct-multipart discriminator, original file/owner/key/full binding, permanent nonreuse. |
| upload_sessions | UUID, allocation, owner, original folder/version, declared size/type, geometry, idempotency key/fingerprint, DB creation/expiry, state/revision, immutable upload ID, dispatch identities, mutually exclusive decision and controlled reason. |
| upload_session_parts | Session/number PK, immutable expected size/SHA-256; frozen selected ETag/checksum manifest on completion. |
| multipart_completion_receipts | One immutable receipt per session, allocation/upload-ID/manifest identity, observed size/checksum/ETag, evidence kind and DB time. |

The separate receipt has different meaning from V25. Part rows establish immutable
authorization and resume identity, not a continuously synchronized Garage mirror.
Require unique allocation/session, owner/idempotency and bound upload identity;
non-cascading allocation references; valid ranges/states; monotonic revisions;
immutable identities/decisions/manifests/receipts. Database guards must require
multipart success evidence and exclude abandonment on every READY path, and
exclude multipart from legacy admission, V25 receipt recording and V26 cleanup.
Preserve authoritative name uniqueness. Index owner history and bounded recovery
including `(state, expires_at, id)`.

Extend ObjectStorageService with concrete initiate/presign/list/complete/abort/
inspect operations; keep SDK types in its adapter. Persisted endpoints must never
cause arbitrary client construction. Order: shared owner hierarchy → fresh file
→ allocation → session → parts/evidence. Read protected state after locking.
Storage I/O remains outside transactions/locks. Receipt commit precedes a separate
READY CAS transaction, preserving bounded ancestry and timeout patterns.

## Garage 2.3.0 findings and unresolved assumptions

| Question | Source-level finding / implication |
|---|---|
| Does cancellation revoke URLs? | PostgreSQL cannot revoke a signature; it may work until Garage observes terminal upload state. |
| Does expiry terminate requests? | Signature authorization checks expiry; this does not stop accepted streaming bodies. Verify clocks, especially recorded JVM/VM skew. |
| Replay/replacement? | Part versions and ETag-based completion selection exist; eligibility is checked before streaming. |
| Late part changes completed bytes? | Part/final versions are separate; isolation is promising source inference, requiring live verification. |
| Abort races parts? | Abort records Aborted without waiting for every streaming handler; acknowledgement is not immediate physical reclamation proof. |
| Terminal-state resurrection? | Object merge makes Aborted dominate; deleted MPU merge discards part references. Propagation/GC remain experimental questions. |
| Delete expired session? | No. Preserve allocation, decisions and unknown evidence. Age, HEAD absence and NoSuchUpload do not resolve uncertainty. |
| Generation DELETE? | Upload identity is not a generation-specific deletion capability; Garage lacks S3 versioning. |

Pinned sources:
[signature](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/common/signature/payload.rs),
[multipart](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/multipart.rs),
[object model](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/model/s3/object_table.rs),
[MPU model](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/model/s3/mpu_table.rs),
[compatibility](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/).

Restrict replay to the same immutable length/checksum per part. Never presign
create/complete/abort/ordinary PUT. If completed-object isolation fails, retain
synchronous upload and disable direct upload. If abort reclamation is uncertain,
retain unresolved evidence rather than fabricating deletion proof.

## Failure and recovery matrix

| Boundary | Proposed handling |
|---|---|
| Create succeeds, ID persistence fails | INITIATION_UNKNOWN; no URLs/repeated Create. Explicit exact-key listing may recover one matching upload under sole-control-plane assumption; absence remains unknown. |
| Part response lost | List authoritative parts; retry missing/mismatched part using original expectations. |
| URL expires | Reauthorize/refresh only OPEN, accessible, unexpired sessions. |
| Replacement during completion | Frozen manifest and immutable signed checksums; mismatch cannot produce READY. |
| Complete succeeds before receipt crash | COMPLETION_UNKNOWN; explicit completed-object evidence recovery, never compensation. |
| Complete timeout and HEAD absence | Preserve unknown; no repeat completion, abort or expiry DELETE. |
| Receipt committed, READY failed | Retry freshly eligible metadata only. |
| READY response lost | Return retained outcome, no storage replay. |
| Cancellation wins first | Permanently exclude completion; abort exact ID outside transactions, retain uncertainty. |
| Folder inaccessible | Stop new capabilities/READY, independently record observed success and retain bytes. |
| Binding/identity drift | Block before storage I/O, never substitute configuration. |
| Restart | Resume durable decisions; restart supplies no writer fence. |

Completed-object adoption is a new gated protocol, not B3. Require original full
binding/key, backend-set session/allocation metadata, expected size/verified
checksum, frozen manifest and exclusive completion decision. HEAD returns
completed data/metadata but is no fence:
[HEAD source](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/get.rs).

Keep original destination/version until READY, with no root fallback. Trash/restore
uses blocked-then-revalidate behavior. Never alter captured file lifecycle/version
under pending purge. Existing folder purge continues UNSAFE_WRITER for admitted
non-READY multipart members. Abort is neither V20 never-written nor V17/V26 delete
proof. READY multipart purge needs explicit completion-evidence checks before
reuse of existing machinery. Unfinished sessions may block folder purge indefinitely;
their removal requires a separate proof extension.

## Minimal web client

File input, Blob.slice, chunk SHA-256 and three parallel requests. IndexedDB keeps
session ID, identity hints and registered checksums, never URLs. Reselected files
must match registered chunk hashes; name/size/mtime alone are insufficient. List
Garage parts and upload missing parts; refresh expired URLs. Network failure means
pause/retry, not cancellation. ETags are opaque tokens. ListParts excludes unfinished
parts and paginates size/ETag/checksum:
[list source](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/list.rs).
Progress combines confirmed parts with active requests. Duplicate completion polls
or recovers the existing decision rather than restarting the file.

## Implementation milestones and verification

| Milestone | Scope / dependency | Acceptance / safety | Documentation |
|---|---|---|---|
| M0 | First, isolated Garage characterization | Presigning/browser headers, checksums/length, replay, delayed parts, complete/abort, response loss/retry | Version-pinned capability matrix and ADR |
| M1 | After viable M0: session reservation | Atomic schema/allocation/session, idempotency, ownership/name guards, legacy exclusion, rollback/decision races | State/lock/API contract |
| M2 | M1: transfer/resume | Initiation recovery, bounded signing, concurrent parts, refresh, pagination and immutable expectations | Client protocol/limits |
| M3 | M2: completion/READY | One dispatcher, frozen manifest, receipt before READY, unknowns, duplicate/recovery and folder races | Recovery matrix/architecture |
| M4 | M3: cancellation/expiry | Abandonment, delayed requests, abort unknowns, explicit bounded recovery and unchanged purge snapshots | Operator/retention runbook |
| M5 | M3–M4: purge verification | Explicit READY evidence checks, non-READY blockers, restart, controls and full regressions | Purge contract/architecture/learnings |

Use random isolated PostgreSQL schemas, committed fixtures and independent
transactions. Force race orders with barriers/latches; assert rows and actual bytes.
Cover authorization/tampering/expiry, parallel/replaced parts, duplicate completion,
dropped responses, identity drift, both folder race winners and every committed
crash boundary. Use individually registered disposable keys/IDs and unchanged
controls. Retain uncertain evidence; never touch public or retained regression
schemas. Report abrupt-process tests separately from boundary simulations.

## Deferred scope and evidence boundary

Defer autonomous upload recovery/expiry, RabbitMQ upload events, broad orphan
scans, historical repair, cancelled-session folder proof, presigned ordinary
PUT/download, overwrite/versioning, cross-device/background browser uploads,
malware processing and comprehensive quotas. M0 is the next slice only.

The original investigation changed no files, migrations, databases, Garage objects,
credentials or infrastructure; ran no tests; made no commit/push; and found clean
Git state. SSH was blocked, so the running Garage image was not independently
confirmed. Saving this report is a separately requested Phase 3B documentation
change and does not upgrade those original evidence claims.
