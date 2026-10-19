# Multipart upload-session foundation — Phase 3C / M1

Consolidation update: [the backend audit](backend-consolidation.md) retires synchronous upload admission/PUT and foreground receipt creation. New uploads use direct multipart only. Earlier flow descriptions, commands and verification totals below are historical milestone evidence; retained historical readers/receipts/expiry/cleanup remain supported where stated in the audit.

Current authorization extension: [Sharing M3](sharing-m3.md) adds owner/initiator
separation and delegated Editor sessions, superseding owner-only authorization
statements in this historical milestone record. Storage/lifecycle guarantees remain.

Current lifecycle extension: [M5 purge compatibility](multipart-purge-compatibility.md)
supersedes the historical blanket multipart purge fence and retained active-name
reservation described in this milestone record. It separates logical disposal
from completed-object deletion and unresolved physical reclamation.

M1 implements PostgreSQL identity, reservation and decision guards only. It follows
the mandatory [M0 capability constraints](garage-multipart-capabilities.md) and
[ADR-0009](decisions/0009-direct-upload-capability-boundary.md). The broader
[direct-upload proposal](direct-upload-architecture.md) remains a future design
outside the boundaries described here.

This document records the M1 foundation. Phase 3D subsequently adds
[M2A initiation, immutable part URLs and resume endpoints](multipart-part-transfers.md).
Statements below about absent endpoints/storage dispatch describe M1 alone.
Phase 3E adds [M3 completion, immutable receipts and READY recovery](multipart-completion-recovery.md).
V27 decision state remains permanent; V29 stores completion/readiness progress
separately and permits READY only with its multipart-specific receipt.

[M4 adds cancellation and explicit expiry](multipart-cancellation-expiry.md).
V30 retains abandonment progress separately without changing immutable V27
decisions or granting physical-removal proof.

## Schema and compatibility

[V27](../backend/src/main/resources/db/migration/V27__multipart_session_identity.sql)
adds immutable `file_storage_objects.upload_protocol`: `SERVER_PUT` by default,
including every existing allocation, or `DIRECT_MULTIPART`. There is no evidence
backfill, binding inference or protocol conversion. V25 admission/PUT receipts and
V24 expiry/preclusion fields are forbidden for multipart by a CHECK constraint.
Allocation keys remain unique and multipart allocation deletion is forbidden.

`upload_sessions` retains one UUID session per allocation through a unique,
non-cascading allocation FK. A deferred constraint requires every multipart
allocation to have a session at commit, excludes sessions on SERVER_PUT, and
requires `write_started` exactly when an initiation ID exists. An insertion guard
checks the exact allocation, owner, file, original folder/file version, full
namespace/endpoint/region/bucket/key binding and declared metadata. Those values,
the canonical name, part geometry, idempotency key/fingerprint and deadline are
immutable. Owner identity is retained without a cascading user/resource FK.

Policy is bounded: positive size through five GiB, fixed eight-MiB parts, ceiling
division for part count (1–640), and a smaller final part where needed. Zero bytes
remain on synchronous upload. PostgreSQL assigns creation time and an exact
24-hour deadline; callers cannot choose or extend either. Expiry is an authorization
boundary, never evidence of writer termination, absent bytes or safe cleanup.
There are no part or receipt tables in M1.

## Internal reservation contract

[MultipartSessionService](../backend/src/main/java/com/vaultdrive/file/MultipartSessionService.java)
is an internal transactional service with no HTTP endpoint or storage dependency.
`reserve(owner, key, request)` validates the owner and accessible destination,
existing filename normalization, positive bounded size and a bounded concrete MIME
type. Null type becomes `application/octet-stream`; otherwise only surrounding
whitespace is removed. MIME type is declared metadata, not content validation.
Keys are case-sensitive ASCII characters `!` through `~`, length 1–128, with no
trimming. PostgreSQL active-name uniqueness remains authoritative.

The SHA-256 fingerprint covers length-prefixed UTF-8 fields: M1 protocol/policy
version, nullable folder UUID, normalized filename, decimal declared size and
canonical type. MIME case/parameters remain significant; the service does not
infer semantic equivalence. Owner/key uniqueness is final database authority.

- Same owner, key and fingerprint returns the original session, file, state,
  revision and deadline, including after expiry, abandonment or folder changes.
  This is identity lookup only; it issues no capability and renews no authorization.
- Same owner/key and different fingerprint throws a conflict before any new
  file/allocation is created. Another owner has an independent key scope.
- A failed reservation rolls back metadata, allocation and session together.
  Different requests always use fresh file UUIDs and storage keys.

Reservation order is shared owner hierarchy → key-specific advisory lock → new
file → allocation → session. The extra lock uses PostgreSQL's two-integer space,
class `1297109833`, with `hashtext(owner + ':' + key)`; it is distinct from the
existing hierarchy/namespace classes. Collisions only serialize extra requests.
Only reservation takes it, before resource rows, so existing-resource operations
cannot invert that order. Folder mutations retain exclusive hierarchy coordination.
Same-name competing keys are resolved by the existing unique index.

## Lifecycle and permanent decisions

```text
RESERVED → INITIATING → OPEN → COMPLETION_DECIDED
    |           |        |
    +-----------+--------+→ ABANDONMENT_DECIDED
```

`beginInitiation` durably captures one immutable initiation UUID and consumes
`write_started` atomically. INITIATING asserts no Garage outcome. `recordUploadId`
is a trusted internal result-recording hook for a future adapter: it requires the
same initiation UUID and persists the exact nonblank upload ID before OPEN.
M1 never obtains a real upload ID itself. Tests use explicitly artificial IDs.

`decide` requires expected revision and a nonnull decision UUID. Completion is
possible only from OPEN; abandonment can win from RESERVED, INITIATING or OPEN.
One state/decision UUID/database timestamp is permanent. A competing decision,
including another UUID for the same kind, conflicts. The identical decision UUID
and kind returns the retained outcome without advancing revision, even when its
original expected revision is stale. Initiation/upload-ID replays similarly retain
their original identities; a replay never changes a terminal state.

Each actual state change increments revision by exactly one; an identical replay
does not. Regressions, skipped states, changed identities, bare revision increments
and decision reversal are rejected by PostgreSQL. Rollback restores both revision
and associated allocation admission. New initiation/OPEN/completion transitions
check PostgreSQL expiry after lock waits; abandonment may be chosen after expiry.

All existing-session operations lock shared hierarchy → fresh scalar file FOR
UPDATE → allocation FOR UPDATE → session FOR UPDATE. Only immutable owner-scoped
file identity is read before row locks. Fresh scalar validation then requires the
original UPLOADING file version/destination/content, exact allocation binding,
active bounded ancestry and no pending purge or retained purge/cleanup evidence.
Foreign and unknown sessions return the same not-found exception. Inaccessible or
changed lifecycle blocks even duplicate decision calls. Restoring the original
folder may restore eligibility if the captured file identity/version still matches;
there is no root fallback or mutation of purge snapshots.

These decision IDs are **not storage dispatch permits**. M1 freezes no parts or
completion manifest and implements no dispatcher. M2/M3 must add immutable
checksum/length expectations, manifest/evidence and non-reassignable dispatch
authority before performing storage operations. Unknown outcomes remain retained;
NoSuchUpload, HEAD absence, age and process death cannot supply that authority.

## Legacy and purge fences

Synchronous reservation, upload and READY remain on SERVER_PUT. Legacy admission
explicitly excludes multipart. Receipt recording/recovery excludes it; database
constraints also prohibit all V25 evidence. B2 refuses UPLOADING multipart as
INCONSISTENT, including RESERVED with `write_started=false`. V26 cannot acquire a multipart
intent. Existing V25/V26 records retain their exact contents.

Every multipart READY write and file deletion is rejected by a file-row trigger:
M1 has no protocol-specific completion or removal evidence. Legacy purge target,
purge work and cleanup intent insertion also reject multipart. Folder execution
reports UNSAFE_WRITER even for a never-initiated multipart reservation, before
creating work or authorizing DELETE. Abandonment supplies no V20/V17/V26 proof.
Unfinished sessions may therefore block folder purge indefinitely.

Deployments must exclude older incompatible application instances from handling
multipart allocations. Database guards fail closed, but are no substitute for
coordinated application locking or a future protocol-specific storage proof.

## Verification and remaining work

[Isolated PostgreSQL tests](../backend/src/test/java/com/vaultdrive/file/MultipartSessionIntegrationTest.java)
use a random schema, committed fixtures and independent transactions. Races observe
`pg_blocking_pids` before releasing the winner; tests cover owner/key deduplication,
changed fingerprints, names, both folder mutation winner orders, both decision
winner orders, duplicate decisions, rollback/revision, binding/ownership rejection,
expiry, deferred pairing, immutable evidence, purge blockers and every READY path.
Mocks assert zero ObjectStorageService/S3 interactions in every M1 test. The upgrade
test migrates a fresh V26 schema containing real V25/V26 evidence and compares its
complete retained rows after V27, excluding only the new protocol column.

Focused command from `backend/`, with the ignored local environment loaded:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=MultipartSessionIntegrationTest,SinglePutDispatchIntegrationTest,StaleUploadExpiryIntegrationTest,UploadFinalizationRecoveryIntegrationTest,KnownSuccessCleanupIntegrationTest' test
```

Full regression verification must redirect existing fixed/default test schemas to
fresh private schemas before running; do not run it against shared public or retained
regression schemas.

Executed on 2026-10-18:

- Focused selection above: **181 passed**, zero failures/errors/skips, including
  all **36 M1 cases**, 50 cleanup, 40 receipt/recovery, 35 expiry and 20 single-PUT cases.
- Full safely isolated backend suite: **954 entries, 934 passed, 20 skipped**, zero
  failures/errors. This includes live Garage, seven live broker cases and continuous
  HTTP → outbox → RabbitMQ → job → Garage verification with readable controls.
- Skips: 18 opt-in M0 capability cases, one explicitly reviewed journal-disposal
  case, and the opt-in process-recovery suite entry. No M0 journal was replayed and
  the retained ambiguous allocation was untouched; no new process-kill claim is made.
- Both completion/abandonment commit orders produced one permanent winner, one
  conflict, unchanged decision identity on replay and exactly one revision increment.
  All M1 cases observed zero storage-boundary/SDK interactions.

Full verification used a disposable copy under `backend/target/m1-final-3a17cda4`.
Only test schema literals and the default test-profile schema were redirected to
fresh names; all production source/resource hashes matched the checked-out files.
The twelve fixed/default replacements are recorded in its ignored `schema-map.json`.
RabbitMQ used the existing isolated `vaultdrive_outbox_test` vhost through a temporary
local SSH tunnel. The continuous harness used the existing
[`ASYNC_E2E_ALIGN_INITIAL_DUE=true` fixture option](async-purge-verification.md#clock-skew-opt-in-for-isolated-verification)
after an unaligned run demonstrated the known JVM/VM skew. This verifies execution
conditional on initial eligibility, not production scheduling under skew. No clock,
VM, broker configuration or credential changed. Earlier fixture assertion/trigger
setup and connection failures were corrected before the passing final runs.
Both verification copies' 24 fixed/default schemas were removed afterward, with
zero remaining under their task prefixes; the temporary tunnel was stopped.

At the M1 boundary, no Garage multipart calls, presigning, parts/checksums, transfer, completion/abort
dispatch, multipart success receipt, READY, autonomous expiry, worker, API or browser
feature is implemented. Browser TLS/CORS/clock prerequisites remain unresolved.
M1 stopped here; the separately implemented M2A extension is linked above.
