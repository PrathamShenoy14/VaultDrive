# ADR-0016: Narrow zero-byte creation with real independent objects

- Status: Implemented
- Date: 2026-10-23

Empty files use a real zero-byte Garage object, a new file UUID and a permanently
reserved unique key with a complete immutable storage binding. There is no shared
sentinel object, implicit virtual object or zero-byte multipart session. GET,
CopyObject and existing proof-backed deletion therefore operate on the same
independent storage representation as other READY files. Metadata-only empty files
would require another download/copy/deletion proof protocol without a benefit to
the MVP's recovery guarantees. Object overhead is not logical quota consumption.

This narrowly supersedes ADR-0010's retirement of all SERVER_PUT admission: only
the authenticated `/files/empty` path can create new zero-byte SERVER_PUT
allocations. General stream upload and POST `/files` stay retired. Nonempty files
still use direct multipart with their original positive-size constraints.

V46 retains `empty_file_creations`, including immutable owner, initiating actor
and display name, original destination/name/type, idempotency key and a single
assignment dispatch timestamp. Admission creates UPLOADING metadata, allocation
and intent atomically. Keys are scoped to owner/initiator; normalized request
differences conflict. Owners and current destination Editors may create; Viewers
cannot. Root belongs to the caller. Owner takeover of recovery does not change
the frozen actor. Replays never confer access after grant revocation.

Only an acknowledged new dispatch commit permits one SDK PutObject attempt with
an empty body and backend-only creation UUID metadata. SDK retries remain disabled.
All Garage I/O is outside transactions. Exact-target positive HEAD requires size
zero, exact MIME, nonblank ETag and matching creation marker before V25's genuine
success receipt commits independently. This relies on the existing trusted sole
control plane, completed-object atomicity and permanent key nonreuse. Neither an
empty response body nor negative HEAD is success evidence. Recovery never repeats
PUT, adopts current storage settings or fabricates a receipt. A process loss
between dispatch commit and call can remain UNKNOWN indefinitely.

Existing receipt-only finalization freshly validates hierarchy/lifecycle, admission
version and destination before READY CAS. V46 also rejects empty READY publication
or metadata removal without matching proof in PostgreSQL. READY and one
UPLOAD_COMPLETED event commit atomically; the event uses the original actor/name
even if an owner or internal receipt recovery finishes it.
Receipt-backed BLOCKED files report STORAGE_COMPLETE until eligible recovery.
Revocation blocks new Editor calls; already admitted storage facts may persist.

Admission lock order is shared owner hierarchy, separate owner/initiator/key
advisory lock, destination namespace, new file with quota trigger, then new
allocation/creation. Existing work takes hierarchy, fresh file row, allocation,
creation; receipt-only finalization reuses hierarchy/file/allocation. Only immutable
identity is read before coordination. Folder structural changes take exclusive
hierarchy and cannot cross these decisions. No lock spans storage calls.

Zero logical bytes change neither used nor reserved. Every insert, including
zero, now locks/checks the owner quota row: exactly full and zero-limit empty
accounts can create empty files, accounts already above their limit cannot admit
another file. This is the chosen zero-byte admission policy. Existing lifecycle
counter transitions and quota notifications remain in the same accounting trigger.

COPY and folder-copy member constraints accept zero; all original source checks,
independent destination allocations, positive COPY receipts, hidden staging and
atomic publication remain. Copies record COPIED once and copy no grants or links.
Rename, move, grants, links, Trash and restore reuse existing authorization.
Downloads GET the real object with Content-Length: 0 and the declared concrete
MIME (default application/octet-stream).

READY empty deletion reuses exact bound SERVER_PUT DELETE and retained byte and
metadata receipts; it does not skip storage because logical length is zero.
Unadmitted reservations retain the existing verified-never-written expiry and
folder purge rules. An admitted UNKNOWN empty write blocks folder removal under
the conservative SERVER_PUT writer barrier; there is no new logical-discard proof
or automatic uncertain-write reclamation. A positive receipt behind an inaccessible
folder can be retained, but publication waits for restored eligibility. Time,
absence, logical quota, abort acknowledgement and metadata removal never prove
Garage block reclamation. Retention and physical-capacity policy remain open.

See [API](../api-contract.md#zero-byte-file-creation-2026-10-23),
[operations](../operational-hardening.md#zero-byte-creation-recovery) and
[verification](../architecture.md#zero-byte-file-verification).
