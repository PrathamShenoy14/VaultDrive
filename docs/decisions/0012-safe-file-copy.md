# ADR-0012: Safe owner-only file copy

Copy M2 composes this file protocol inside the bounded hidden-tree jobs in
[ADR-0013](0013-bounded-folder-copy.md). Its member idempotency has a separate
database namespace; public M1 keys/replays and storage recovery remain supported.

- Status: Implemented
- Date: 2026-10-22

Copy M1 uses a separate COPY allocation protocol and retained PostgreSQL intent,
dispatch and positive receipt. Each admission creates an independent file UUID,
immutable key and full-size quota reservation in one transaction. The caller must
own the active READY source and destination (root is allowed). Editors and Viewers
cannot copy. Case-sensitive filename collisions fail with 409; no overwrite or
automatic suffix is attempted. Source length/type and destination/name are frozen.
Idempotency is owner/key scoped and retained without expiry; changing that request
returns 409. Replays return the original operation even after resource removal.

Execution is explicit and bounded in the request, with a retained status endpoint.
There is no copy queue, job, outbox notification, scheduler, lease or reassigned
dispatcher. RESERVED may retry read-only source preflight. Dispatch freshly
revalidates source/destination under coordination, commits a single-assignment
timestamp and source ETag, then invokes CopyObject once outside the transaction.
The SDK has one attempt. UNKNOWN never authorizes another CopyObject.

The [S3 API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html)
allows errors inside HTTP 200 and source ETag conditions. We consume the SDK result,
use source-if-match and replace metadata with a backend-only operation UUID.
Read-only exact-key HEAD verifies the copied marker, length, content type and
nonblank ETag, with the original immutable binding. A separate receipt commit
precedes freshly eligible READY/version finalization and the atomic COPIED event.
This relies on the existing trusted single control plane, atomic completed-object
visibility and permanent key nonreuse; it is not a checksum or physical block proof.
Source deletion after dispatch does not invalidate a completed independent copy.

No negative observation, provider error, timeout, age or crash establishes success,
failure or writer termination after dispatch. An acknowledged dispatch commit
followed by process loss before the call can therefore remain UNKNOWN forever.
Recovery uses positive HEAD or an existing receipt and never repeats the write.
Cancellation permanently abandons READY authority, releases logical quota through
FAILED, and retains every possible late storage effect. Only predispatch source
ineligibility produces FAILED automatically, while a hidden destination defers.

READY copies share existing exact-key deletion receipts with COPY-specific
completion verification. Unfinished sealed folder members use a separate
copy_discarded_at logical receipt. That can remove metadata and release its charge
without claiming byte deletion. Late positive facts can be retained after removal
without resurrecting a file. Legacy PUT/multipart receipts and allocations are
never rewritten or adopted. Retained identities and history have no new erasure
or retention policy.

Lock order is shared owner hierarchy, admission-only owner/idempotency advisory
lock, existing file rows in lexical UUID order, allocations in that same order,
then operation. Admission has one existing source and takes destination namespace
before inserting its new file (quota trigger), allocation and operation. Existing
quota locks remain last among existing accounting rows. Finalization/cancellation
lock before re-reading; file versions and existing purge barriers remain. Folder
purge takes copy operation progress after allocation and before work/member/job
locks. It commits permanent publication abandonment with logical proof before
the final job fence; metadata removal checks proof without updating copy progress.
No copy/resource lock follows the job fence. No I/O spans these locks. See
[the API, recovery table and runbook](../file-copy.md).

Folder copying, frontend, notifications, automatic uncertain-object reclamation,
large-copy multipart support, process-kill recovery and performance measurement
remain outside M1. The supported logical size is the existing positive five-GiB
file policy; the live tests exercise small and multi-part source objects, not a
five-GiB capacity claim.
