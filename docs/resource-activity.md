# MVP general file and folder activity history

General history is separate from [permission activity](sharing-m5.md). It records
successful resource transitions in PostgreSQL, without a new event framework,
outbox event, consumer, notification, or external I/O.

## Events and snapshots

V41 creates `resource_activity_events`. Each immutable UUID event stores the
actor ID/display-name snapshot, owner ID, resource type/ID/name, operation and
PostgreSQL `clock_timestamp()` instant. Before/after columns retain name, immediate
parent ID, explicit Trash state and file status. An absent side is null; a null
parent on a present side means root. Folder status is always null.

| Event type | Successful transition |
|---|---|
| UPLOAD_COMPLETED | Winning UPLOADING -> READY, including receipt-backed recovery |
| FOLDER_CREATED | New folder inserted and flushed |
| RENAMED | Normalized effective name actually changes |
| MOVED | Immediate parent actually changes |
| TRASHED | Explicit active -> trashed transition |
| RESTORED | Explicit trashed -> active, including collision name/root fallback |
| PERMANENTLY_DELETED | Metadata removal and durable finalization receipt commit |

Name is the after name, or the before name for deletion. Before/after values
refer to this resource only. A folder move/Trash/restore does not synthesize events
for descendants whose rows did not change. Recursive permanent deletion records
one event for every file/folder actually removed, at its batch's completion time.
Purge acceptance, byte deletion alone, cancellation/expiry, failed uploads, reads,
downloads and automatic permission expiry are not resource events.

Actor is the authenticated caller for metadata changes, independently of resource
ownership. Editors can create owner-owned folders, rename and trash under the
existing authorization matrix. Async deletion attributes the operation to its
owner, the only authorized permanent-delete requester, including inherited members.

V41 also adds nullable `activity_actor_id` and `activity_actor_display_name` to
`multipart_completions`. New manifest freezes store the authenticated decision
actor and name. V29's whole-row guard makes them immutable. Owner takeover or
receipt-backed recovery preserves that original decision actor, even following
revocation or profile changes. Merely initiating/reserving a session emits no event.
No existing decision, receipt, session, file, allocation, quota or manifest is
rewritten. Pre-V41 frozen decisions lack that caller snapshot: a later READY event
uses their retained original initiator and the display name available at
finalization. Historical SERVER_PUT recovery attributes its known original uploader
(the owner). Missing accounts use `[deleted account]`, without changing identity.
These legacy attributions cannot reconstruct an unknown historical completion caller.
`actorAttribution` makes the source explicit: CALLER for the retained authenticated
mutation/decision caller, UPLOAD_INITIATOR for legacy upload attribution, and
DELETION_REQUESTER for the owner behind async permanent deletion.

## Transaction and replay guarantees

Metadata services capture before state after existing hierarchy coordination and
fresh authorization, flush the mutation, then use a MANDATORY history writer in
the same transaction/connection. File metadata events retain the optimistic version
winner. Restore keeps hierarchy -> eligible file row -> destination namespace.
Folder mutations retain exclusive owner coordination.

Multipart retains hierarchy -> fresh file -> allocation -> session -> completion/
receipt. Only the successful READY CAS appends history; READY replay does not
invent or backfill an event. Positive object receipts still commit independently
before READY. Event insertion failure rolls back READY and its progress, leaving
the receipt available for metadata-only recovery with no repeated Complete.

Purge retains its hierarchy/resource/allocation/work/run order and final job fence.
Scalar resource snapshots are read under those existing locks immediately before
removal. Removal, proof and history roll back together on event failure, stale
checkpoint or expired job fence. Replays that observe finalization receipts append
nothing. No hierarchy lock/transaction spans Garage, signing or RabbitMQ I/O.

PostgreSQL guards event shapes and rejects UPDATE/DELETE. Partial uniqueness on
`(resource_type, resource_id, event_type)` additionally prevents duplicate create,
upload-completion and permanent-deletion events. Ordinary mutations may recur;
their uniqueness boundary is the successful coordinated database transition.
No-ops, failed authorization/validation/constraints, stale optimistic writes,
transaction rollback, session-idempotency replay and worker/broker retries add
no duplicate events. An intentional A -> B -> A -> B sequence records three
changes. There is no new idempotency contract for HTTP metadata mutations.

No resource/account/grant foreign keys or cascades target history. Evidence
survives purge and account removal, matching permission activity's existing
retention boundary. Account erasure/retention policy remains undecided; this
milestone adds no deletion, pruning or repair API. No pre-milestone backfill or
history for direct SQL/test fixture mutations is promised. Deploy the updated
mutation/worker code together; older binaries do not append this history.

## API and visibility

Authenticated `GET /api/v1/activity` returns 200 and `Cache-Control: no-store`:

```json
{"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}
```

Each content entry has `id`, `actorId`, `actorDisplayName`, `actorAttribution`, `ownerId`,
`resourceType`, `resourceId`, `resourceName`, `eventType`, `beforeName`,
`afterName`, `beforeParentId`, `afterParentId`, `beforeTrashed`, `afterTrashed`,
`beforeStatus`, `afterStatus`, `occurredAt`. UUIDs are strings, times are UTC ISO
instants, inapplicable values are null. Names are historical snapshots, not a
lookup of the current resource or account.

| Filter | Contract |
|---|---|
| page, size | Defaults 0/50; nonnegative signed integer page; size 1..100; 64-bit offset |
| resourceType | Optional exact FILE or FOLDER |
| resourceId | Optional exact UUID; requires resourceType; no subtree expansion |
| eventType | Optional exact event type from the table above |
| actorId | Optional exact UUID; filters only the caller's owned history |
| from, to | Optional inclusive occurrence instants, UTC years 0001..9999, maximum 9999-12-31T23:59:59.999999Z; from <= to |

Filters combine with AND. Invalid UUIDs/enums/date bounds/pagination return the
common sanitized 400 envelope; absent/invalid authentication returns 401.
Ordering is `occurred_at DESC, id DESC`. Count and page share one PostgreSQL
statement snapshot, including timestamp ties and empty/out-of-range pages.
Offset pages may shift as new events arrive; cross-request snapshots and commit
order are not promised. Transactions/statements/lock waits are bounded to 5/3/2
seconds. A database timeout returns sanitized 503 and `Retry-After: 2`.

**Only the immutable event owner can read it.** SQL binds owner to the JWT subject
before filtering/counting/paging; there is no owner selector. Editors/Viewers,
event actors, former collaborators and link holders receive no history for another
owner's resources. Known foreign and unknown IDs both yield empty owner-scoped
pages. This deliberately conservative MVP avoids disclosing old names, private
parents, historical access or activity from before a grant. No recipients, ancestry
paths/names, storage keys, secrets or signed URLs are stored or returned. Parent
UUID snapshots belong to the owner who is allowed to inspect them.

History remains available to the owner during Trash/pending purge and after
metadata removal. It supplies no capability or resource authorization. Ordinary
operations still resolve current permissions, ancestry and lifecycle; revoked
actors cannot recover access from an event or retain a creator privilege.
Permission history and sharing notifications keep their existing separate rules.

## Verification and limits

The new isolated PostgreSQL API suite covers metadata event kinds, before/after
values, owner/Editor identity, profile snapshots, account/resource removal,
permission revocation, no-ops/failures, per-operation outer rollback and event
failure, uncommitted invisibility, mandatory transactions, immutable/shape/unique
guards, inclusive combined filters, equal timestamps, pages and bounded timeout.
Existing deterministic sharing races now assert only the authorized/optimistic
winner's activity. Multipart tests assert one READY event through duplicate and
concurrent recovery, event-insert failure, owner takeover and revocation. File and
folder purge tests assert event/proof atomicity, retry, stale-fence rollback,
standalone/folder participation, recursive retained evidence and logical disposal.
Live Garage completion asserts one event with exact bytes and retained receipts.

Executed commands/results and gated checks are recorded in
[architecture evidence](architecture.md#general-activity-verification). Query
cost, latency, capacity, physical reclamation, browser release and retention/erasure
remain unmeasured or undecided. This milestone stops before copy, quota
notifications, collaborator history feeds or frontend work.
