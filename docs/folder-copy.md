# Copy M2: bounded, resumable folder copy

Copy M2 uses the verified [Copy M1 protocol](file-copy.md) for every file. PostgreSQL
owns the snapshot, checkpoints and publication decision. There is no copy broker,
scheduler, lease takeover or automatic storage retry. Execution is caller driven.

## API

Authenticated owner-only POST `/api/v1/folders/{sourceId}/copy` requires
`Idempotency-Key` (1..128 printable ASCII characters excluding spaces) and:

```json
{"destinationFolderId":"<owned-folder-uuid-or-null>","name":"Project copy"}
```

Omitted/null destination means the owner's root. Name is required, trimmed,
case sensitive, 1..255 characters with existing folder-name validation. The exact
root name is reserved; an active folder collision returns 409 without overwrite,
merge or suffix allocation. Existing file/folder namespaces remain separate.
Copy into the source or any current descendant returns 409. Viewer/Editor access
returns 403; strangers, missing and lifecycle-hidden resources return uniform 404.
No grants, public links or source history are copied. Normal destination inheritance
applies after publication. Owner folder capabilities now include COPY.

Admission performs no storage I/O. It commits the entire snapshot and independent
folder identities, returning 202 with Location pointing to
`/api/v1/folder-copy-operations/{id}`. No partial admission survives rollback.

```json
{"id":"<job-uuid>","sourceFolderId":"<source-uuid>",
 "destinationFolderId":null,"folderId":"<new-root-uuid>","name":"Project copy",
 "state":"ACTIVE","checkpoint":0,"totalMembers":12,"completedFiles":0,
 "totalFiles":8,"revision":0,"createdAt":"<UTC-instant>","disposalOperationId":null}
```

IDs/names are retained admission snapshots, including after moves or removal.
The ordinal checkpoint counts consecutively finished snapshot members, including
folders. completedFiles counts committed M1 READY operations and can exceed the
file count represented by the checkpoint after an interrupted caller. No storage
keys, provider responses, bindings, claims or source ancestry paths are returned.
Revision increases on durable job progress/decision changes; it is not an HTTP
precondition or a version of independently changing file evidence.

| Endpoint under `/api/v1/folder-copy-operations/{id}` | Behavior |
|---|---|
| GET | Owner-only historical status, 200; no dispatch or recovery |
| POST `/execute` | At most four member steps; reserve/execute only undispatched M1 work |
| POST `/recover` | At most four steps; receipt/positive HEAD recovery of existing M1 operations; never allocates a file or dispatches CopyObject |
| POST `/cancel` | Permanently close unpublished job publication, including during an admitted call; COMPLETED conflicts |
| POST `/discard` | For FAILED/CANCELLED only, idempotently accept existing bounded folder purge of the hidden destination |

Mutation responses use 202 for ACTIVE/BLOCKED/UNKNOWN and 200 for
COMPLETED/FAILED/CANCELLED. All include Location and Cache-Control: no-store;
nonterminal results, including GET, include Retry-After: 2. Poll no faster than
once per two seconds with client backoff and a bounded retry policy. Unknown and
foreign job IDs both return 404. No owner selector, long poll or completion SLA.
Discard returns the retained job plus disposalOperationId: poll that ID through
`/api/v1/deletion-operations/{id}` for actual purge progress. A terminal copy status
alone does not mean disposal finished.

Owner/key uniqueness has no expiry. The same normalized source/destination/name
returns the same job, including after deletion; rebinding the key returns 409.
Replay confers no current resource authority. Internal M1 keys use a separate database
namespace, preserving every valid public M1 key and its original replay.

Deploy V43 and the updated mutation/access/copy code together. Mixed old/new
application binaries are not a supported folder-copy rollout.

## Snapshot, bounds and locks

Admission takes the existing exclusive owner hierarchy lock before protected
reads. All supported file and folder writers contend with it. One PostgreSQL
statement captures accessible active folders and READY files; explicitly trashed,
pending-purge, non-READY and unpublished copy subtrees are excluded. It freezes
source UUIDs, names, parent mapping, file sizes/types and new destination UUIDs.
It is a membership/identity snapshot, not a versioned storage backup. Immutable
source allocations and M1 source-if-match protect each later storage operation.

The limit is **1,000 total members including the root**, maximum member depth 64
(root depth one). Oversized/deeper requests return 409 before admission. The
external destination's ancestry is conservatively limited to 192 folders so the
copied tree fits the existing 256-folder resolver bound. Later deeper destination
moves block progress until eligibility is restored. Admission has a ten-second
transaction, three-second statement and two-second lock-wait budget. Enumeration
may time out safely on a broad source; no capacity or latency claim is made.

All destination folders are created in that bounded admission. Each later file
reservation creates its independent file/key/COPY allocation and links exactly
one M1 operation to the immutable member in the same short transaction. A
checkpoint commits only after reading durable M1 READY truth. Replay after a lost
response or process restart uses that same member/operation, never another key or
quota charge. PostgreSQL guards retained membership, mapping/linkage, terminal
decisions, revision/checkpoint monotonicity and receipt-backed publication.

Folder transactions acquire exclusive owner hierarchy first; M1's existing
file/allocation/operation and admission namespace locks precede the job checkpoint
write. M1 execution retains shared hierarchy and its original lock order.
Exclusive coordination serializes competing folder callers and lifecycle changes;
the one-assignment M1 dispatch permission handles overlapping callers during I/O.
No job-only lock holder subsequently acquires resource locks. No transaction or
hierarchy lock spans Garage. Each execute/recover request stops after four member
steps or a 20-second admission window, checked between steps. A started M1 step
can finish beyond that window using its existing bounded calls. This is a work
bound, not a hard whole-request deadline. Atomic publication additionally validates
at most 1,000 members and appends at most 1,000 folder events in one ten-second
transaction; timeout preserves hidden staging and committed M1 evidence.

## Visibility, lifecycle, quota and activity

The public access resolver hides every destination member until the job is
COMPLETED, including individually READY files. Folder scalar/JPA mutation checks
also reject staging members. Lists, search, downloads, grants, links and ordinary
owner mutations cannot expose or move a partial member out. Trash lists omit
hidden copy members. Polling is the owner's only destination inspection route.
After all members finish, the job publication decision and folder COPIED history
commit atomically. Any event failure rolls back publication/checkpoint; M1 receipts
and READY files remain safely hidden for metadata-only recovery.

Source renames/moves/additions do not change snapshot names or membership. A source
file that becomes inaccessible before reservation fails the job; after reservation,
M1 freshly checks source lifecycle before dispatch and can fail that member.
A source deleted after dispatch does not invalidate positive destination evidence.
There is no rescan, substitution, rollback of published bytes or automatic skipping
of a failed member. Destination Trash/purge blocks dispatch/finalization/publication;
restore can permit explicit continuation. Parent purge owns its sealed subtree and
may logically discard unfinished copies using existing COPY-specific proof.

Quota is incremental per file, not a whole-tree reservation. Full logical length
is reserved with M1 admission, then transferred once to used at M1 READY, even
while hidden. Replay charges nothing twice. Exhaustion rolls back that member's
file/allocation/link and records BLOCKED; previous progress stays charged. Restore
capacity and execute explicitly. Admission itself creates no file charge.
UNKNOWN/positive receipts/BLOCKED release nothing. Job cancellation alone releases
nothing: READY members remain used and unfinished members reserved until eligible
file abandonment or purge metadata removal. Discard submits existing purge; it
releases logical charge in bounded removal batches, retaining allocations and
all possible late storage effects. Unknown storage estimates can overlap reserved
bytes and do not measure physical Garage consumption or GC.

M1 appends one COPIED event per successfully committed READY file with the job's
admission actor/name snapshot. Those owner-only historical file events can exist
while the tree is hidden or subsequently cancelled; they do not assert whole-job
completion. Folder COPIED events are emitted only on successful whole-tree
publication, with the same frozen owner actor, absent before side, active after
side and null folder status. Staging, failed admission, cancellation and discard
acceptance create no folder COPIED/FOLDER_CREATED events. Existing successful purge
removals retain their normal deletion activity. No replay duplicates activity.

## Recovery and operator procedure

| Recorded state/boundary | Safe action |
|---|---|
| ACTIVE / no file operation yet | execute allocates the next captured member; recover never allocates |
| Reserved member / caller stopped | execute runs M1 preflight; recover cannot dispatch |
| BLOCKED / quota or retained-state conflict | Inspect owner usage/current destination; repair the cause, then execute |
| BLOCKED / destination Trash | Restore destination, then recover existing receipts or execute undispatched work |
| UNKNOWN / dispatched member | recover positive destination HEAD/receipt only; execute cannot redispatch it |
| READY member / checkpoint lost | execute or recover advances from durable M1 truth without another charge/write |
| All members READY / publication rolled back | recover publishes metadata/history atomically without storage calls |
| FAILED | Inspect source/member state; permanent job failure cannot be rearmed; cancel/discard or start a new job/key |
| CANCELLED | Publication stays closed forever; an admitted call may still finish and retain its receipt |
| Cancelled/failed hidden tree | discard once, then poll disposalOperationId; retry discard reuses that event |
| Parent already trashed/pending purge | discard conflicts; restore an eligible parent or follow the existing parent purge; never create overlapping purge intent |
| COMPLETED | Historical proof of publication, not current accessibility; use ordinary resource APIs |

Operators with database access can inspect the bounded retained member/evidence
map without changing it (bind the exact owner/job UUIDs; select no storage secrets):

```sql
SELECT m.ordinal, m.kind, m.source_id, m.destination_id,
       c.id AS file_copy_operation_id, c.state AS file_copy_state,
       c.dispatched_at IS NOT NULL AS dispatched,
       c.receipt_at IS NOT NULL AS storage_verified
FROM folder_copy_jobs j JOIN folder_copy_members m ON m.job_id=j.id
LEFT JOIN file_copy_operations c ON c.id=m.operation_id
WHERE j.id=:job_id AND j.owner_id=:owner_id
ORDER BY m.ordinal LIMIT 1000;
```

The job state records the last coordinated outcome; GET does not inspect Garage
or predict new lifecycle changes. A failed HTTP transaction may leave earlier
committed progress intact. After restoring eligibility, choose execute for missing
reservations/RESERVED work and recover for UNKNOWN/receipt/checkpoint recovery.

GET first. Retain the original Location/idempotency key. Do not reset a checkpoint,
operation dispatch timestamp, allocation, receipt or key; do not manually free
quota or infer storage absence/writer termination from age. A dispatch-commit/crash
gap can remain UNKNOWN indefinitely. Cancel closes publication without recalling
the call, and discard preserves COPY's separate logical-disposal evidence.
No automatic unknown-object reclamation, source pinning, unbounded-tree snapshot,
process-kill recovery harness, retention/erasure API or autonomous executor is added.
See [quota operations](storage-quotas.md), [folder purge](folder-purge-design.md),
[ADR-0013](decisions/0013-bounded-folder-copy.md) and
[executed verification](architecture.md#copy-m2-verification).
