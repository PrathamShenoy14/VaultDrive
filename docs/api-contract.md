# MVP API contract consistency

## Backend API freeze and frontend handoff (2026-10-23)

The [freeze record](api-contract-freeze.md), [complete endpoint inventory](api-endpoint-inventory.md),
[generated OpenAPI 3.1 artifact](openapi.json) and [frontend integration guide](frontend-integration.md)
are the current MVP handoff. They supersede historical milestone exclusions and
message-only/empty-error descriptions in older feature records. The export covers
actual Spring MVC routes and Java record DTOs; representative PostgreSQL/HTTP
checks validate response schemas and contract drift. No public documentation
endpoint or new runtime dependency is added. Browser deployment requires the
same-origin HTTPS API proxy; Garage direct-upload CORS is separate. The
[real-browser gate](browser-multipart-verification-2026-10-23.md) remains open.

## Zero-byte file creation (2026-10-23)

Authenticated POST `/api/v1/files/empty` requires `Idempotency-Key` (1..128
printable ASCII characters, no spaces) and JSON `{folderId: UUID|null, name:
string, contentType: string|null}`. There is no byte body, size selector or
multipart session. Name uses existing trimmed, case-sensitive filename rules.
MIME defaults to application/octet-stream; supplied values must be concrete,
valid, at most 255 characters and contain no control characters. Owner or effective
Editor on the active accessible destination can create; Viewer gets 403,
strangers/missing/hidden/inaccessible destinations get 404. Null folder means
the caller's root. The destination owner owns the file and quota charge.

Returns 200 when READY, otherwise 202; both include no-store and Location
`/api/v1/empty-file-creations/{id}`. Body: `{id, fileId, state, storageVerified}`.
States: RESERVED (no write admitted), UNKNOWN (one dispatch, no positive receipt),
STORAGE_COMPLETE (receipt retained, READY blocked), READY, FAILED, REMOVED.
REMOVED means file metadata is absent, not physical reclamation. `storageVerified`
reports the retained success receipt, not current object presence. Status contains
no storage key, target, signed URL or access capability. GET the Location for
status; POST its `/recover` suffix returns 200 with the same shape and performs
positive-only recovery, never PUT. Creation replay can dispatch an unadmitted
RESERVED intent; an admitted write is never repeated, even after lost responses.

Idempotency is retained per owner/initiator/key. Same normalized folder/name/type
returns the original identity; changed input or active-name collision returns
409. Keys do not expire. The owner may poll/recover retained records after resource
removal. An initiating Editor must still have Editor authority on the original
accessible destination; other callers get 404. Replays do not grant file access.
Invalid names/types/keys return 400, missing authentication 401, bounded process
admission 429. Existing common error envelopes apply. Zero-byte admission uses
the metadata lane and freshly checks the owner's locked quota row; exactly full
or zero-limit accounts may add zero bytes, already-over-limit accounts get 409.

READY requires a real unique-key zero-byte object and independently committed
positive verification receipt. Existing file and public-link downloads return
Content-Length: 0, normal attachment/security headers and an empty body.
Authenticated downloads use declared/default MIME; public-link downloads always
use application/octet-stream. Rename/move/share/Trash/restore/permanent deletion retain their
existing permissions and contracts. File and bounded folder copy accept empty
sources through the normal independent COPY object/receipt path. Multipart
admission still rejects zero. POST `/api/v1/files` remains retired.

See [storage, races, uncertainty and purge guarantees](decisions/0016-zero-byte-files.md).

## Operational admission and health (2026-10-23)

Authentication, multipart control (including creation/signing), authenticated
downloads and metadata/search/copy each have independent bounded per-process rate
and concurrency admission. Rejections occur before business work and can precede
authentication/authorization errors. They return 429, `Cache-Control: no-store`,
`Retry-After: 1` and
`{"status":429,"error":"TOO_MANY_REQUESTS","message":"Request capacity exceeded"}`.
Public links retain their separate limits and message with the same envelope and
headers. The retry hint is a minimum delay, not guaranteed admission. A received
429 is safe to retry after backoff; reuse original idempotency keys. Lost responses,
202/503 and refresh rotation retain their protocol-specific uncertainty rules;
never automatically repeat uncertain Create/Complete/Abort/Copy or refresh.

Download capacity covers storage opening through actual stream exit, including
client errors. Cancellation/timeout closes queued/running storage streams; a
running callback retains its slot until it returns. Already admitted downloads may
finish after revocation. The bounded async stream timeout is five minutes.
Limits/defaults and edge prerequisites are in [the operational policy](operational-hardening.md#admission-and-retry-contract).

Public GET `/api/v1/health` remains process liveness with 200 UP. New public GET
`/api/v1/health/ready` returns no-store and
`{"status":"UP"|"DOWN","service":"vaultdrive-api"}`, status 200 or 503.
It reads only a cached dependency/processing snapshot; disabled/uninitialized/stale
probes fail closed. No dependency names, resource details or counts are public.
Readiness does not establish physical reclamation or browser release.

There is no HTTP administrator endpoint. Bounded local operator replay/budget
extension preserves retained identities and does not authorize new storage calls.
Deletion polling may move from FAILED back to PROCESSING after an audited job
budget extension; original operation UUID and completion-evidence precedence remain.
See [operator procedures](operational-hardening.md#safe-operator-inspection-and-replay).

## Storage quota notifications (2026-10-22)

The existing authenticated `/api/v1/notifications` list, `/unread-count` and
`/{id}/read` APIs now include owner-only `STORAGE_QUOTA_THRESHOLD_CROSSED` alerts.
Optional `eventType` accepts this fourth type. Existing JWT recipient scope,
pagination/filter/count/order, no-store, unread and idempotent mark-read semantics
remain. Unknown/foreign IDs both return 404; missing JWT returns 401.

Every item adds nullable `quota`; grant items return null. Quota items return
`quota: {threshold: 80|90|100, logicalUsageBytes: <int64>, limitBytes: <int64>,
occurredAt: <UTC instant>}`, with `actionable:false` and null resource fields/role.
Usage and limit are immutable event-time snapshots; `createdAt` remains delivery
persistence time. Only the storage owner receives them, including Editor-caused
crossings. No actor identity or resource/storage details are exposed.

Thresholds use positive logical used plus reserved charge, exact percentage
comparisons, all newly crossed boundaries per transition and strict-below rearming.
Limit edits participate. Empty zero-limit accounts stay quiet; positive charge
at zero limit reaches all boundaries. Notifications never authorize admission;
mutations check fresh quota state. See [delivery, thresholds and operations](quota-notifications.md).

## Bounded folder copy (Copy M2, 2026-10-22)

Owner-only authenticated POST `/api/v1/folders/{sourceId}/copy` accepts the same
required Idempotency-Key syntax as M1 and `{destinationFolderId: UUID|null, name:
string}`. Name is the exact normalized destination root name; active folder
collisions and source/descendant destinations return 409. Viewer/Editor receives
403; strangers/missing/hidden resources receive 404. Admission snapshots at most
1,000 active folder/READY file members, depth 64, and returns 202 without storage
I/O. Owner folder capabilities include COPY. No grants or public links are copied.

GET `/api/v1/folder-copy-operations/{id}` returns retained owner-only status:
`{id, sourceFolderId, destinationFolderId, folderId, name, state, checkpoint,
totalMembers, completedFiles, totalFiles, revision, createdAt, disposalOperationId}`.
States are ACTIVE, BLOCKED, UNKNOWN, FAILED, CANCELLED and COMPLETED. POST suffixes
`/execute` and `/recover` advance at most four member steps; recover neither
allocates a file nor dispatches CopyObject. `/cancel` closes publication permanently;
`/discard` separately accepts idempotent existing folder purge for FAILED/CANCELLED.
Poll the returned disposalOperationId using deletion-operation polling. Mutation
results are 200 for terminal copy states and 202 otherwise; GET is 200. Location,
no-store and nonterminal Retry-After: 2 follow M1. Foreign/unknown jobs return 404.

The complete destination is hidden from ordinary reads/search/downloads/mutations
until atomic publication. Each file uses the existing one-dispatch M1 protocol,
independent identities and incremental quota. Cancellation retains charges until
eligible abandonment/removal and never erases uncertain evidence. Internal M1
member keys have a separate database namespace; public M1 keys remain compatible. Snapshot,
checkpoint, visibility, lifecycle, quota, activity and operator details are in the
[folder-copy contract](folder-copy.md) and [ADR-0013](decisions/0013-bounded-folder-copy.md).

## Safe file copy (Copy M1, 2026-10-22)

Authenticated owner-only POST `/api/v1/files/{sourceId}/copy` accepts required
`Idempotency-Key` (1..128 printable ASCII excluding spaces) and
`{destinationFolderId: UUID|null, name: string}`. Omitted/null destination is root.
The source must be accessible READY, and the destination owned and accessible.
Viewer/Editor receives 403; strangers/missing/hidden resources receive 404.
Name uses existing trimmed case-sensitive validation. Collision or quota exhaustion
returns 409 without overwrite, suffix allocation or partial reservation.

Responses expose `{id, sourceFileId, fileId, destinationFolderId, name, state,
revision, storageVerified, createdAt}`. New file/key/allocation and quota charge
are independent of the source. No storage identity or provider evidence is public.
The owner file capability list adds COPY. Same owner/key/normalized request replays
the retained operation; changing source/destination/name returns 409. Historical
replay/status survives Trash and removal without granting current resource access.

GET `/api/v1/copy-operations/{id}` returns 200 and performs no storage work.
POST suffixes `/execute`, `/recover`, `/cancel` respectively retry only undispatched
RESERVED work, probe positive evidence or finalize an existing receipt, and
permanently abandon eligible unfinished publication. READY cannot be cancelled.
Mutation responses are 200 for READY/CANCELLED/FAILED and 202 for
RESERVED/UNKNOWN/STORAGE_COMPLETE/BLOCKED, with Location pointing to status.
All responses use no-store; nonterminal responses include Retry-After: 2.
Unknown/foreign operation IDs return 404. There is no autonomous worker/long poll.

After dispatch, timeout, failure or absence retains uncertainty and reservation;
CopyObject is never repeated. READY requires positive object verification,
independently committed receipt and fresh durable metadata finalization. Logical
cancellation/folder discard can release charge while retaining possible storage
effects. See [full protocol, bounded calls, response semantics and runbook](file-copy.md)
and [ADR-0012](decisions/0012-safe-file-copy.md). Folder copy, quota notifications
and frontend are outside M1. Historical exclusions below predate this extension.

## General resource activity (2026-10-22)

Authenticated `GET /api/v1/activity` returns 200, `Cache-Control: no-store` and
`{content, page, size, totalElements, totalPages}`. Defaults are page 0/size 50,
with nonnegative integer pages, size 1..100 and 64-bit offsets. Optional AND
filters are `resourceType` (FILE/FOLDER), `resourceId` (UUID, requires type),
`eventType`, `actorId` (UUID), and inclusive occurrence instants `from`/`to`.
Dates use the same UTC 0001..9999 bounds as search; from must not exceed to.
Invalid parameters return sanitized 400; missing authentication returns 401.

Events are UPLOAD_COMPLETED, COPIED, FOLDER_CREATED, RENAMED, MOVED, TRASHED, RESTORED,
and PERMANENTLY_DELETED. Entries expose immutable `id`, `actorId`,
`actorDisplayName`, `actorAttribution`, `ownerId`, `resourceType`, `resourceId`, `resourceName`,
`eventType`, `beforeName`, `afterName`, `beforeParentId`, `afterParentId`,
`beforeTrashed`, `afterTrashed`, `beforeStatus`, `afterStatus`, `occurredAt`.
Missing before/after sides are null; status is null for folders. A present side
with null parent means root. Restore snapshots include root fallback/collision
renaming. Permanent deletion means durable logical metadata completion, with
one event per removed member; it does not assert physical reclamation.

Only the event's immutable owner sees it, including Trash, pending purge and
removed resources. JWT ownership filters before count/page; there is no client
owner selector. Being the actor, a current/former Editor/Viewer, recipient or link
holder grants no history visibility. Foreign/unknown resource filters return the
same empty page. No recipient, ancestor path/name, storage identity or secret is
exposed. Private-parent redaction on ordinary resource responses is unchanged.
History confers no resource authority; revoked access remains revoked.

Order is `occurred_at DESC, id DESC`; totals/page share one statement snapshot,
including empty/out-of-range pages. Concurrent inserts can shift later offset
requests. Read transaction/statement/lock budgets are 5/3/2 seconds; timeout returns
503 with `Retry-After: 2`. Snapshots and events commit with their database mutation;
no-ops, failed transactions, idempotent replays and purge retries add no duplicates.
Upload actor is the frozen completion decision caller, preserved through recovery;
pre-V41 decisions use the retained initiator because the decision caller is unknown.
`actorAttribution` distinguishes CALLER, legacy UPLOAD_INITIATOR and async
DELETION_REQUESTER. See [schema, attribution, retention and guarantees](resource-activity.md).
Permission history remains at `/api/v1/permission-activity` with its own contract.
The historical milestone exclusions below predate this activity extension.

## Filename search and filtering (2026-10-22)

Authenticated `GET /api/v1/search?q=report` returns 200 with `Cache-Control: no-store`
and `{content: [{resourceType: "FILE"|"FOLDER", resource: <existing file/folder DTO>}],
page, size, totalElements, totalPages}`. `resource.id` opens the resource directly;
the visible `folderId`/`parentFolderId` supports navigation. A null parent can mean
root or redacted private parent; clients must not infer private ancestry. No owner
ID, storage identity, ancestry names, facets or inaccessible sibling data is exposed.

| Parameter | Contract |
|---|---|
| `q` | Required, 1..255 Unicode code points, nonblank, no ISO controls; preserved literally, including spaces |
| `resourceType` | Optional `FILE` or `FOLDER`; omitted searches both |
| `scope` | `ALL` (default), `OWNED` (caller is owner), `SHARED` (other owner's accessible resource) |
| `mimeType` | Optional exact case-insensitive MIME type/subtype, at most 255 characters; stored declaration parameters (such as charset) are ignored; filter accepts no parameters or wildcards |
| `minSizeBytes`, `maxSizeBytes` | Optional inclusive nonnegative signed 64-bit byte bounds |
| `createdFrom`, `createdTo` | Optional inclusive creation instants |
| `modifiedFrom`, `modifiedTo` | Optional inclusive `updated_at` instants; metadata modification, not content-version history |
| `page`, `size` | Zero-based page, default 0; size default 50, 1..100; same integer and 64-bit offset rules as listings |

Date values are ISO instants with an offset, bounded to UTC years 0001..9999
(maximum `9999-12-31T23:59:59.999999Z`). Lower bounds cannot exceed upper bounds.
MIME/size filters select files only; combining them with `resourceType=FOLDER`
returns 400. Filters combine with AND. Invalid parameters return the common
sanitized 400 envelope without echoing rejected values; absent authentication is 401.

Matching uses PostgreSQL `lower(name) LIKE lower(:pattern)` with literal substring
semantics. `%`, `_` and the SQL escape character `!` are escaped; backslash is
literal. PostgreSQL database locale determines Unicode case mapping; no accent
folding, Unicode normalization, tokenization, fuzzy matching or content search
is promised. Results order by `lower(name) COLLATE "C", name COLLATE "C",
resource_type, id` ascending. Type breaks equal file/folder names before UUID.

The V32 `resolve_resource_access` function is the authorization authority. Owned
resources and strongest current direct/inherited Viewer/Editor grants are included,
once per resource even with overlapping grants. Only active folders and READY
files qualify. Trashed/pending roots or ancestors, invalid/cyclic/over-256 ancestry
fail closed. Direct grants permit discovery under private active parents, with
the same parent redaction and fresh informational capabilities as resource reads.
Share links alone do not establish authenticated search permission.

One SQL statement resolves access and lifecycle, filters, counts and pages across
owners, including empty and out-of-range pages. Indexed name/possible-owner
candidate reduction confers no authority; every result passes the existing resolver.
No application traversal, per-result authorization lookup, cached permission, or
visibility-dependent early return is used. Like Shared with me, search takes no
multi-owner hierarchy locks. An overlapping uncommitted change can leave the old
committed snapshot visible; a subsequent request sees committed revoke/downgrade,
move, inheritance and lifecycle changes. Operations must reauthorize. Pages are
deterministic for unchanged data, not a frozen snapshot across requests.

Read budgets are five seconds per transaction, three per statement and two for
lock waits; bounded database timeouts return sanitized 503 with `Retry-After: 2`.
V40 installs `pg_trgm` in `public` and partial GIN `lower(name)` indexes on active
folders and active READY files. Deployment needs the PostgreSQL extension package
and extension-install privilege (or prior installation in `public`). Existing
owner/parent and recipient/target grant indexes support scope and resolution.
Short terms may scan and broad searches may time out; query latency/capacity and
index speedups have not been benchmarked. SQL execution is not constant time.

Search verification and remaining gated checks are recorded in the current
[architecture evidence](architecture.md#filename-search-verification).
The historical milestone exclusions below do not exclude this later search API.

The later [MVP storage quota contract](storage-quotas.md) adds the authenticated
usage route and quota exhaustion response below. The historical no-quota boundary
later in this document describes only its original milestone.

GET `/api/v1/users/me/storage` returns 200, `Cache-Control: no-store` and the
authenticated caller's owner-scoped usage:

```json
{"limitBytes":10737418240,"usedBytes":0,"reservedBytes":0,"availableBytes":10737418240,"uncertainStorageBytes":0,"uncertainStorageAllocations":0,"unknownSizeAllocations":0}
```

All byte fields are nonnegative 64-bit integers. Available excludes used and
outstanding reserved bytes. Uncertain fields are a separate allocation inventory,
not added to available. Missing bearer credentials return 401. A quota-exceeding
upload-session request returns 409
`{"status":409,"error":"CONFLICT","message":"Storage quota exceeded"}`
after destination authorization. It exposes no other owner's limit or usage.
Authorized idempotency replay retains its original charge.

Implemented on 2026-10-20. This is the current resource, Trash, deletion-polling
and error contract. Earlier sharing/lifecycle records preserve their milestone
boundaries; the changes below supersede their capability, folder-list, Trash and
error-envelope descriptions. No migration or storage protocol changes are needed.

## Resource responses and authorization

File metadata: `{id, name, folderId, contentType, sizeBytes, createdAt, updatedAt,
effectiveRole, allowedCapabilities}`. Folder metadata: `{id, name, parentFolderId,
createdAt, updatedAt, effectiveRole, allowedCapabilities}`. IDs are UUID strings,
times are UTC ISO instants, roles are `OWNER`, `EDITOR` or `VIEWER`.
A private immediate parent is null in both reads and Editor mutation responses.
No storage identity, owner identity or private ancestry enters these DTOs.

Capabilities describe actions permitted by the response's fresh permission and
lifecycle snapshot. They are informational; every subsequent operation authorizes
again. Revocation, downgrade, move, Trash or purge may invalidate a response before
the client acts. A capability is never a credential or an authorization input.

| Active resource | Owner | Editor | Viewer |
|---|---|---|---|
| File | READ_METADATA, DOWNLOAD, COPY, RENAME, MOVE, TRASH, MANAGE_GRANTS | READ_METADATA, DOWNLOAD, RENAME; conditional TRASH | READ_METADATA, DOWNLOAD |
| Folder | READ_METADATA, LIST_CHILDREN, CREATE_FOLDER, UPLOAD, COPY, RENAME, MOVE, TRASH, MANAGE_GRANTS | READ_METADATA, LIST_CHILDREN, CREATE_FOLDER, UPLOAD, RENAME; conditional TRASH | READ_METADATA, LIST_CHILDREN |

Editor TRASH requires effective Editor on the immediate parent as well as the
target. A null/root, private or Viewer-only parent omits TRASH, even with a direct
Editor grant on the target. Strongest direct/inherited grants are recomputed;
parent editability is derived in the same SQL statement as resource access.
Active responses never advertise RESTORE or PURGE. Non-READY files and resources
with inaccessible, trashed or pending-purge ancestry have no ordinary metadata,
listing or download response. Owner-only restore/purge remain lifecycle checks.

Owner hierarchy coordination precedes fresh reads. Grant/folder mutations retain
exclusive coordination; file writes retain shared coordination, optimistic
versions and existing eligible-row/namespace locks. No lock key/order, storage
boundary, multipart identity, dispatch or uncertain-writer rule changes.

## Listings and pagination

All endpoints below accept zero-based `page` (default 0, nonnegative) and `size`
(default 50, range 1..100). Invalid or overflowing integers return 400. The offset
uses 64-bit multiplication, including out-of-range pages. Responses use:

```json
{"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}
```

| GET endpoint | Content and deterministic order |
|---|---|
| `/api/v1/folders` | Actor's root folders; `lower(name) COLLATE "C", name COLLATE "C", id` ascending |
| `/api/v1/folders/{id}/children` | Authorized immediate folders; same folder order |
| `/api/v1/files?folderId={id}` | Authorized immediate READY files; database `name, id` ascending; omitted folder selects actor's root |
| `/api/v1/shared-with-me` | Direct entry points `{resourceType, resource}`; database `name, id` ascending |
| `/api/v1/files/trash` | Actor's explicitly trashed READY files; `deleted_at DESC, id ASC` |
| `/api/v1/trash/folders` | Actor's explicitly trashed folders; same Trash order |

Eligibility, count and page use one PostgreSQL statement snapshot, including empty
or out-of-range pages. Ineligible descendants never contribute to Trash totals.
Trash is owner scoped, so collaborators see only their own Trash. A visible shared
folder does not expose the owner's root or private siblings. Root and child folder
endpoints now return an envelope instead of an array. SQL folder ordering replaces
Java case folding; Unicode case-pair ordering can change. File Trash ordering also
changes from name to deletion time with a UUID tie breaker.

These are offset pages, not a frozen export: concurrent permission/lifecycle/name
changes or inserts can move entries between requests. Stable unchanged data has a
deterministic order; cross-request snapshot isolation is not promised. The original
listing milestone added no search, cursor, arbitrary sorting or combined feed;
the later search contract above adds its own combined feed. Trash reads have
a five-second transaction budget, three-second statement and two-second lock-wait
bounds; timeout returns sanitized 503 and Retry-After.

## Common Trash entries

Both Trash feeds use the same entry schema:

```json
{
  "id":"<uuid>", "resourceType":"FILE", "name":"report.pdf",
  "originalParentId":null, "deletedAt":"2026-10-20T00:00:00Z",
  "purgeRequestedAt":null, "lifecycleState":"TRASHED",
  "allowedCapabilities":["RESTORE","PURGE"]
}
```

`resourceType` is FILE or FOLDER. Original parent IDs belong to the authenticated
owner. `deletedAt` is explicit deletion time; inherited Trash is not synthesized.
`lifecycleState` is TRASHED or PENDING_PURGE. A pending-purge root remains listed
with its timestamp and an empty capability array until metadata removal. Every
resource below a pending-purge folder is excluded, including separately trashed
children and independently pending file roots. Missing, cross-owner, cyclic or
more-than-256-folder ancestry fails closed. Folder PURGE is omitted if a folder
in its subtree already has pending purge; RESTORE can remain eligible.

Restore preserves the existing root fallback/name-conflict rules. Pending ancestry
blocks fallback as before. Active-resource deletion still returns 204; restore
routes and their successful response schemas are unchanged. No bytes are removed
by Trash. Pending requests cannot be restored or independently purged again.

## Deletion acceptance and polling

`DELETE /api/v1/files/{id}/permanent` and
`DELETE /api/v1/trash/folders/{id}/permanent` still return 202, now with:

```json
{"operationId":"<uuid>"}
```

Headers include `Location: /api/v1/deletion-operations/{operationId}`,
`Retry-After: 2` and `Cache-Control: no-store`. The UUID is the original atomic
outbox identity, returned only after intent commits. Acceptance performs no
storage I/O. Repeated/ineligible requests retain their existing 404 semantics;
this change does not introduce purge idempotency or replacement jobs.

Authenticated GET on Location returns 200 with:

```json
{
  "operationId":"<uuid>", "resourceType":"FOLDER", "resourceId":"<uuid>",
  "requestedAt":"2026-10-20T00:00:00Z", "state":"ACCEPTED",
  "reasonCode":null, "logicallyCompletedAt":null
}
```

Only the original owner can poll, including after resource metadata disappears.
Unknown IDs, other owners and unrelated outbox identities return the same 404.
The read has a five-second transaction/three-second statement budget and uses
indexed retained event/target/work/run/job/progress evidence in one statement.
It does not traverse the subtree, contact Garage/RabbitMQ, claim work or expose
storage keys, worker claims, exception text, member names or internal counters.

| State | Evidence / public reasonCode |
|---|---|
| ACCEPTED | Durable intent, no job or completed publication yet |
| PROCESSING | Job PENDING/RUNNING, or publication completed awaiting ingestion |
| BLOCKED | Known unavailable standalone target (TARGET_UNAVAILABLE), incomplete member blockers (MEMBER_BLOCKED), quarantined delivery without a job (DELIVERY_QUARANTINED), or generic job SUCCEEDED without logical finalization (METADATA_PENDING) |
| FAILED | Job FAILED (EXECUTION_FAILED), or outbox FAILED without a job (DELIVERY_FAILED) |
| LOGICALLY_COMPLETED | Matching retained file metadata-finalization receipt or folder FINALIZED timestamp; logicallyCompletedAt is populated |

Logical evidence takes precedence over generic job failures/success: another
participating folder worker can finalize a standalone target whose original job
failed. Job failure precedes blocker display. An existing job takes precedence over
outbox exhaustion/quarantine. Blockers are recorded facts, not a prediction of all
future failure paths. PROCESSING does not guarantee a currently running worker or
a completion deadline; disabled/stopped workers can leave durable pending work.
Publisher, consumers, workers and reconciliation retain their disabled defaults.

Poll at the suggested interval and apply client backoff; GET is safe to retry.
Responses are no-store; noncompleted/nonfailed states include Retry-After: 2.
The hint is not a completion SLA. Failed work needs operator inspection, and
blocked work may require repair/recovery; GET never retries or rearms anything.
A recorded terminal failure can later become LOGICALLY_COMPLETED through an
existing authorized recovery/participating purge path. Retain the Location;
no operation-search or retention/cleanup API is introduced. Pre-outbox purge
requests have no fabricated operation. Purge outbox/targets/run/work receipts must
remain retained for this contract; their eventual retention policy is undecided.

Logical completion never promises physical storage reclamation, multipart part
cleanup, Garage GC, absence of late admitted effects or writer quiescence.
SUCCEEDED alone is not logical completion. Missing resource rows alone are not
completion evidence. Existing multipart logical-discard receipts retain their
uncertain effects and storage identities without introducing cleanup authority.

## Errors and HTTP semantics

Controller advice, bearer authentication/denial, cookie transport admission and
public-link failures use `{status, error, message}`. `error` is the uppercase HTTP
symbol and is the stable machine code; message is explanatory text, not a code.
Validation messages never echo rejected values. Bean field errors are sorted by
field/message; malformed JSON, UUIDs, enums, booleans, headers and numeric binding
errors produce sanitized BAD_REQUEST. Domain validation retains its explanatory
message in the same envelope. Public link failures deliberately retain uniform
NOT_FOUND, even for malformed IDs, missing/wrong tokens and unsupported routes or
methods, avoiding an existence/token oracle.

| HTTP | error / meaning |
|---|---|
| 400 | BAD_REQUEST; invalid request or domain validation |
| 401 | UNAUTHORIZED; missing/invalid authentication; Bearer challenge on protected APIs |
| 403 | FORBIDDEN; visible resource but insufficient privilege, or invalid CSRF admission |
| 404 | NOT_FOUND; missing, stranger, lifecycle/ancestry-hidden target; all invalid public links |
| 405 | METHOD_NOT_ALLOWED; unsupported authenticated method (Allow retained) |
| 406 | NOT_ACCEPTABLE; unsupported requested response media type, sanitized JSON envelope |
| 409 | CONFLICT; namespace, optimistic write or retained multipart decision conflict |
| 412 / 428 | PRECONDITION_FAILED / PRECONDITION_REQUIRED; stale/missing grant If-Match |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 429 | TOO_MANY_REQUESTS; bounded authentication/multipart/download/metadata or public-link admission; Retry-After: 1 |
| 500 | INTERNAL_SERVER_ERROR; sanitized unexpected/database failure |
| 502 / 503 | BAD_GATEWAY / SERVICE_UNAVAILABLE; authenticated/public download open failure / unavailable dependency or bounded database timeout |

Owner/Viewer/Editor and stranger rules, public token isolation, no-store,
no-referrer/nosniff and private-parent redaction remain. Public streaming cannot
replace an already committed response with JSON after an I/O interruption.
Error normalization changes prior mixed-case labels, Spring default error bodies,
empty security-filter responses and public message-only bodies. Clients must
migrate to the common envelope and page/Trash shapes in the same release.

## Verification and limits

Contract tests cover effective roles/private parents, parent-only Trash authority,
Trash/restore/pending ancestry, deterministic bounded pages/counts, deletion
acceptance/polling/redaction and error envelopes. Real PostgreSQL lock-wait tests
observe blocking and verify both Trash endpoints return sanitized 503 within their
configured read bound. Existing PostgreSQL concurrency harnesses add
parent-downgrade/capability reads in both commit orders. Real
PostgreSQL/Garage purge tests assert accepted/processing, blocked member, failed
execution and logical completion before generic job completion and after metadata
removal.

No schema, lock-order, upload/uncertain-writer protocol, general rate limiter,
search, quota, copy, activity-history feature or frontend is added. Performance,
large-manifest query cost, browser release prerequisites, process-kill recovery and
physical reclamation remain outside this milestone.

## Executed verification

Verified on 2026-10-20 against PostgreSQL 17.11, inspected Garage 2.3.0 and
RabbitMQ 4.3.6. The final full run's default application contexts used
`vaultdrive_test.api_contract_final_verification`; suites with explicit isolation
retained their test schemas. Applied migration history/default-schema checksum
mismatches were not repaired. Normal application execution switches stayed off.

- API/security/concurrency focused selection: 179 passed, no failures/errors/skips.
- Deletion/Trash/polling focused selection: 157 passed, no failures/errors/skips.
- Final HTTP/download/timeout/worker review: 36 passed, no failures/errors/skips.
- Stabilized worker selection: 14 passed, no failures/errors/skips.
- Final clean full suite: **1,326 tests, 1,306 passed, 20 skipped, zero failures/errors**.
  Includes 12 isolated live RabbitMQ tests, 27 enabled Garage multipart cases,
  PostgreSQL constraints/lifecycle/races and the continuous HTTP-to-Garage harness.

Initial broad verification found obsolete array/empty-202 contract expectations
and a pre-existing heartbeat-test race: its scheduled monitor could renew the
fixture's deliberately expired lease before the replacement claim. Expectations
now assert the new response/polling contract. The heartbeat test drives polling
and maintenance explicitly, with bounded asynchronous cancellation observation;
production worker, lease and renewal behavior are unchanged. Final verification
passed after these corrections.

Reproduce from `backend/` with the task-owned localhost AMQP tunnel described in
[the isolated runbook](async-purge-verification.md):

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
.\mvnw.cmd -o clean test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=api_contract_final_verification' '-Dspring.flyway.schemas=api_contract_final_verification' '-Dspring.flyway.default-schema=api_contract_final_verification'
```

The harness reported `initialDueAligned=false` for both file and folder: no
initial due-time adjustment was needed. It verified file completion and folder
DISCOVER -> DELETE_FILES -> DELETE_FOLDERS -> FINALIZED, duplicate delivery,
unchanged receipts and cleanup of only seven owned objects plus its fresh schema,
queue and exchange. Other synthetic verification schemas/evidence remain retained.
The task-owned AMQP tunnel was closed after verification.

| Gated suite | Skipped | Separate prerequisite, deliberately not enabled |
|---|---:|---|
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and process-kill runbook |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | Separately reviewed retained-run UUID |

These skipped scenarios are unverified by this milestone. Browser interoperability,
physical reclamation, performance/capacity and retention policy remain unverified
or undecided as previously documented. No push or infrastructure/configuration
change is included.

For blocked/failed operations, use the existing [publication recovery](outbox-publishing.md),
[file recovery](file-purge.md), [folder recovery](folder-purge-design.md) and
[missing-ingestion recovery](missing-job-reconciliation.md) procedures. Preserve
original event/target/proof identities; the polling endpoint adds no rearm,
repair or cleanup authority. Whitespace, staged-path/diff review and 136 local
documentation file links passed before commit.
