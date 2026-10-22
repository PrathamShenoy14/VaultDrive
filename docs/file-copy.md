# Copy M1: file copy API and recovery operations

[Copy M2](folder-copy.md) adds bounded owner-only folder jobs using this same file
protocol. Internal folder-member idempotency uses a separate database namespace;
all previously valid public M1 keys/replays remain supported. The M1
dispatch/receipt/recovery protocol continues unchanged.

Copy is owner-only. It requires an accessible READY source and an accessible
destination folder owned by the authenticated caller, or the caller's root.
Viewer/Editor access returns 403; strangers, missing and lifecycle/ancestry-hidden
resources return the existing 404. No grants or public links are copied; normal
destination-folder inheritance applies to the new file. An owner
file's current capability list includes COPY; every request reauthorizes.

## API contract

POST `/api/v1/files/{sourceId}/copy`, with bearer authentication,
`Content-Type: application/json`, and required `Idempotency-Key` (1..128 printable
ASCII characters excluding spaces):

```json
{"destinationFolderId":"<owned-folder-uuid>","name":"report copy.pdf"}
```

Omit destinationFolderId or set it null for root. Name is required and uses existing
trimmed, case-sensitive file validation (1..255 characters, no path separators or
controls). The complete name is used; no source extension is implicitly appended.
Active UPLOADING/READY destination-name collisions return 409 and overwrite nothing.
The source's logical size and content type are copied; bytes remain behind
ObjectStorageService. Copy supports existing positive files up to five GiB.
The source must have its original retained binding matching this backend; unknown
historical bindings or configuration drift return 409 and cannot be inferred from
today's settings. Destination-folder inheritance applies normally to the new file.

The body is a retained operation snapshot:

```json
{"id":"<operation-uuid>","sourceFileId":"<source-uuid>","fileId":"<new-file-uuid>",
 "destinationFolderId":"<folder-uuid-or-null>","name":"report copy.pdf",
 "state":"READY","revision":3,"storageVerified":true,"createdAt":"<UTC-instant>"}
```

Revision increases on each durable state/evidence change; clients must not assume
an exact final number. IDs/destination/name are admission snapshots, including
after ordinary moves, renames, Trash or metadata deletion. READY means the copy
was published, not that it is still currently accessible. Use ordinary metadata
endpoints for current lifecycle/capabilities. No key, binding, ETag, signed URL,
credential, source private ancestry or provider response is returned.

| Endpoint under `/api/v1/copy-operations/{id}` | Behavior |
|---|---|
| GET | Owner-only retained status, 200, no storage I/O or dispatch |
| POST `/execute` | Retry RESERVED source preflight/admission; otherwise return retained status without dispatch |
| POST `/recover` | At most one read-only destination HEAD, or receipt-only metadata finalization; never CopyObject |
| POST `/cancel` | Permanent logical abandonment of an eligible unfinished copy; READY conflicts |

Mutation results return 200 for READY/CANCELLED/FAILED and 202 for
RESERVED/UNKNOWN/STORAGE_COMPLETE/BLOCKED, with Location pointing to GET status.
All responses use Cache-Control: no-store. Nonterminal results, including GET,
use Retry-After: 2. Poll at most once per two seconds and bound client polling;
there is no server long-poll, automatic worker, progress percentage or completion
deadline. Each transaction has a ten-second timeout, three-second statement and
two-second lock-wait bounds. Each provider call has ten-/five-second total/attempt
bounds, and one SDK attempt. Initial execution uses at most source HEAD, CopyObject
and destination HEAD; recovery uses at most one HEAD. Database errors use the
existing sanitized envelope (timeouts 503); uncertain provider calls return the
retained nonterminal state. Binding drift or invalid retained evidence returns 409.

Owner/idempotency-key uniqueness is durable and has no expiry. Same normalized
request returns the same file/operation; different source/destination/name returns
409 without a new reservation. Replay after deletion still returns owned historical
status. It grants no resource access or new storage authority. Unknown/foreign
operation IDs return the same 404. There is no client-supplied owner or storage key.

## Durable protocol and quota

1. Under hierarchy coordination, validate ownership, source READY and ancestry,
   lock the source/allocation, serialize admission by owner/key and destination
   namespace, then insert new UPLOADING file, COPY allocation and immutable intent.
   The existing file trigger reserves the full source length atomically. Quota
   exhaustion returns generic 409 with no partial file/allocation/intent.
2. Outside all transactions, HEAD the original source. In a fresh transaction,
   recheck source/destination ownership and lifecycle, bind its ETag, permanently
   set write_started and dispatch timestamp, and commit UNKNOWN. Only that
   newly committed permit authorizes one CopyObject. Ineligible source fails
   before dispatch and releases the reservation; ineligible destination defers.
3. Outside transactions, CopyObject uses source-if-match and metadata REPLACE
   with vaultdrive-copy=<operation UUID>. A timeout/error retains UNKNOWN.
   A normal result still requires a positive exact-key HEAD with matching marker,
   declared length/type and valid ETag under the retained binding.
4. Commit the positive receipt independently. A subsequent transaction rechecks
   destination identity/version, accessible ancestry and purge barriers, then
   atomically changes UPLOADING to READY, moves reserved bytes to used, and appends
   one COPIED activity event with the admission actor/name. Rollback preserves
   the receipt and reservation for metadata-only recovery.

The copy is charged separately from its source. Trash/pending purge remain charged.
Explicit cancellation commits permanent CANCELLED plus eligible FAILED/version exactly once;
it releases logical charge/name and never deletes bytes or recalls a permitted
storage call. A later copy of the same name gets a new UUID/key. Uncertain admitted
allocations remain in uncertainStorageBytes/Allocations even after cancellation or
removal. These estimates can overlap reserved bytes and do not measure Garage GC.

## Recovery guarantees and operator procedure

| Retained boundary | Safe next action |
|---|---|
| RESERVED; source HEAD unavailable | Retry execute or cancel; no storage write was admitted |
| Source lifecycle changed before dispatch | FAILED; no CopyObject, reservation released |
| Dispatch committed, call never made or response unknown | UNKNOWN; recover with positive HEAD only, or logically cancel |
| HEAD absent/mismatched/unavailable | Keep UNKNOWN/reservation; never infer failure or repeat CopyObject |
| Positive object, receipt transaction lost/rolled back | Recover with another positive HEAD; no repeated copy |
| Receipt committed, READY rollback/unknown commit | Recover metadata only; READY replay appends no event |
| Destination trashed/pending purge | Retain reservation/evidence; BLOCKED after receipt, no READY until eligible |
| Source deleted after dispatch | Retain source allocation identity; destination receipt can finalize independently |
| Cancellation during an admitted storage call | Late effects remain tracked; never resurrect CANCELLED/FAILED |
| Unfinished destination folder purged | Separate logical discard and metadata removal; retain allocation/intent/possible late receipt |
| READY copy purged | Existing exact-key byte and metadata receipts, verified against COPY completion evidence |

Use GET first, with the owner's bearer identity. For UNKNOWN, invoke recover once
and inspect the retained result; repeat only bounded read-only probes when useful.
Do not reset dispatch, substitute another key/binding, rewrite receipts, manually
free quota, infer quiescence from age, or DELETE an unknown object. If the user
abandons an eligible unfinished copy, cancel records the permanent decision.
Pending-folder intent is immutable; cancel cannot change a sealed member snapshot.
The normal folder worker locks copy progress after allocation and before work/job,
and commits CANCELLED with copy_discarded_at before its final job fence. It preserves
the sealed UPLOADING/FAILED file snapshot and releases quota only on subsequent
metadata removal, while retaining all physical uncertainty. Neither that receipt
nor cancellation can satisfy byte deletion or legacy never-written/PUT proofs.

A crash after dispatch commit but before storage dispatch may remain unresolved
forever: absence is not proof. No automatic physical cleanup is provided for
cancelled/removed unknown copies. Existing purge recovery retains original event,
allocation and proof identities. See [file purge](file-purge.md),
[folder purge](folder-purge-design.md), [quota repair](storage-quotas.md) and
[ADR-0012](decisions/0012-safe-file-copy.md).

## Verification

FileCopyIntegrationTest uses a fresh PostgreSQL schema, committed UUID fixtures,
independent transactions, latches and observed pg_blocking_pids. GarageFileCopyIntegrationTest
uses fresh journaled keys, a real multi-part source, exact downloaded bytes,
independent source/destination deletion, destination Trash during copy, and a
one-request TCP proxy that consumes a successful CopyObject response and drops
it downstream. Positive recovery never sends another CopyObject. Cleanup targets
only this run's positively verified destinations and known complete source/control
objects; ambiguity retains the journal/allocations. Test schemas/evidence are retained.

From backend/, after read-only Garage 2.3.0 attestation:

```powershell
. .\.env.local.ps1
$env:GARAGE_COPY_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
.\mvnw.cmd -o '-Dtest=FileCopyIntegrationTest,S3CopyStorageTest,GarageFileCopyIntegrationTest' test
```

Executed totals and separately gated checks belong in
[architecture evidence](architecture.md#copy-m1-verification). Five-GiB capacity,
latency, physical block independence/reclamation, multi-node failure and process-kill
recovery are unverified. Independent identity/lifecycle does not imply physical
block duplication. Folder copy, frontend and quota notifications are outside M1.
