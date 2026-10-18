# Multipart purge compatibility — M5

M5 extends [M4 cancellation/expiry](multipart-cancellation-expiry.md) and
[M3 completion](multipart-completion-recovery.md). It separates namespace release,
metadata removal, completed-object deletion and physical storage reclamation.
These are different outcomes, with different evidence.

## Guarantees and API behavior

| State/action | Logical outcome | Storage outcome |
|---|---|---|
| Cancel or explicit expiry wins | Eligible UPLOADING becomes FAILED, version increases once, active name is released in the same transaction as intent. | Allocation/session/dispatch/evidence remain. No physical proof follows, including NEVER_INITIATED or ABORT_ACK. |
| Folder trash | New capabilities, first dispatch permissions and READY finalization are blocked by fresh ancestry checks. | Already permitted I/O may finish; bytes are unchanged by trash itself. |
| Folder restore before purge | Freshly eligible session operations resume; cancellation remains permanent. READY keeps its existing object/key. | No object copy or inferred cleanup. |
| Permanent folder deletion, non-READY multipart member | Sealed exact membership permits a durable logical-discard receipt and later metadata removal, including INITIATING or uncertain completion. | Zero Create, Complete, Abort, HEAD, ListParts or DELETE from this purge branch. A late permitted completion may still create an object; it remains tracked and retained. |
| Permanent deletion, READY multipart file | Existing file/folder purge machinery removes metadata only after exact retained completion and DELETE receipts, subject to existing folder barriers. | Delete the immutable completed-object key using its original binding. Retry only that same key. No part-block/GC guarantee. |

Existing endpoints, JWT ownership checks, status codes and response shapes stay
unchanged. Cancellation can still return 202 UNKNOWN/BLOCKED even though its name
has been released. FAILED upload metadata appears in neither normal file listing
nor READY Trash. Cancellation/session reads remain available while the exact
original destination is accessible. After folder metadata removal these reads
return the existing non-leaking 404. Same idempotency key/payload still returns the
original retained session identity; use a new key to reserve a new upload.

**Folder purge SUCCEEDED/FINALIZED now means all intended metadata decisions were
completed and all READY object deletions were acknowledged. It does not mean that
unfinished multipart allocations or all Garage disk blocks were reclaimed.**
No new public physical-reclamation status or cleanup endpoint is introduced.

## V31 and implementation

[V31](../backend/src/main/resources/db/migration/V31__multipart_purge_compatibility.sql)
is append-only; applied V1–V30 migrations are unchanged. It adds the nullable
`file_purge_work.multipart_discarded_at` receipt, excludes simultaneous byte/
never-written proof and permits metadata-finalization only after the appropriate
receipt. Guards require an exact sealed manifest/work/allocation/session binding,
a matching live pending folder root when logical proof is first recorded, and
immutable proof thereafter. Retained manifests validate FINALIZED replay even
after the root disappears. Receipt creation/checkpoint and metadata removal/
finalization receipt remain atomic under the existing job fence.

The existing partial unique index already excludes FAILED files; no namespace
index change is needed. Cancellation/expiry changes only file status, version and
updated time, after the permanent abandonment and cancellation intent exist.
Original session identity, filename, version, destination and expiry stay immutable.
The eligibility wrapper admits exactly that original-version-plus-one FAILED
abandonment for retained public cancellation operations, with unchanged full
binding, owner, bounded ancestry and purge/cleanup barriers. It cannot grant new
part or completion authority because those operations require OPEN or the mutually
exclusive completion decision.

No historical file or manifest is rewritten during migration. An eligible old M4
intent can release its name on explicit repeated cancel. GET and recovery do not
silently change old file state. Existing captured folder manifests remain exact:
folder purge can logically dispose of their unfinished multipart members without
rewriting a snapshot. Corrupt/mismatched identities still block.

READY eligibility requires the original allocation, permanent completion decision,
single dispatch, frozen manifest and immutable positive completion receipt. V25
PUT recovery, V26 cleanup and multipart never-written proofs stay excluded. The
existing physical-deletion adapter still checks the current client against the
retained original binding; stored endpoints never construct clients.

## Coordination and recovery

Cancellation retains hierarchy shared → fresh file → allocation → session →
cancellation, with name release while the same file lock is held. Folder structural
operations retain exclusive owner hierarchy coordination. Folder file work retains
hierarchy shared → fresh root/file → allocation → work/progress → job fence last;
metadata removal retains exclusive hierarchy and bottom-up pages. Reading retained
multipart evidence adds no new lock order: every mutable session operation already
locks the file/allocation first. No Garage I/O or signing spans a database transaction.

- Before cancellation commit: intent and name release both roll back. After commit:
  the name stays released even if abort permission/response/acknowledgement is lost.
  Repeating cancel never advances the released file version again.
- Completion finalization wins before folder trash/purge: discovery records READY
  and requires completed-object deletion. Folder mutation wins: READY remains
  blocked and exact non-READY membership permits logical disposal. Tests force
  both serial orders with real PostgreSQL blocking observations.
- Already committed initiation/complete/abort permissions remain non-reassignable.
  Late completion receipts and abort acknowledgements can record facts after folder
  removal but cannot resurrect the file, rewrite membership, create physical proof
  from logical proof or trigger an uncertain redispatch. Late Create identity
  adoption remains unsupported; an unbound result stays retained.
- Crash before logical receipt/checkpoint commit: both roll back. Crash after commit:
  a replacement claim reuses the same receipt. File removal and metadata receipt
  commit together; root removal and FINALIZED commit together. Retained proof, not
  missing metadata, permits replay.
- Crash after READY DELETE but before byte receipt: retry the same original key.
  A committed byte receipt skips further DELETE. Uncertain storage outcomes retain
  metadata and use existing bounded retry behavior. New same-name uploads use new
  file IDs/keys; retained allocations prevent older work from deleting newer bytes.

Workers, publisher, consumer, reconciliation and expiry remain disabled or explicit
as before. M5 adds no worker, scheduler, Redis, infrastructure or retry lease.
Stop incompatible old workers before applying V31 and deploying M5; old binaries
must not execute these new proof semantics.

## Physical limitations and operations

A DELETE acknowledgement is completed-key deletion evidence under the accepted
single trusted control-plane, immutable part length/checksum and permanent key
nonreuse contract. It is not generation-specific DELETE and proves neither accepted
part-handler termination nor physical part-block reclamation. M5 adds no provider
version fence, age fence, absence-based cleanup or second abort sweep.

For non-READY logical discards, retain allocations, upload sessions, part expectations,
completion/cancellation decisions, dispatch IDs, receipts, manifests and purge work.
`multipart_discarded_at` with null `bytes_deleted_at`/`never_written_at` means **storage
reclamation unresolved**, even if an abort was acknowledged or no object is visible.
A late positive completion receipt does not automatically upgrade the discarded
work to physical deletion. No repair/GC procedure or retention deletion is supplied;
independently established provider capabilities would be needed for that extension.
Do not reset jobs, clear dispatch IDs, reuse storage keys or delete retained evidence.

## Verification

[PostgreSQL lifecycle tests](../backend/src/test/java/com/vaultdrive/worker/MultipartPurgeIntegrationTest.java)
use fresh random schemas and independent committed fixtures. They cover namespace
release/rollback/legacy reservations, old intent adoption, READY restore naming,
all unfinished initiation/completion/cancellation states, unknown deletes, exact
byte versus logical receipts, crash/checkpoint/claim replacement, late facts and
both completion/purge winner orders. Updated M1/M3/M4 tests assert the M5 boundary.

[Live Garage tests](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartPurgeIntegrationTest.java)
use production adapters and newly journalled isolated allocations, with a readable
unrelated control. They cover READY deletion with an accepted late part, lost abort
response plus folder purge, permitted completion after folder removal, and an old
deletion receipt replay while a newer same-name object remains exact. The verifier's
known storage outcomes authorize only disposal of its own fixtures; they grant no
application repair authority.

Run from backend after loading the ignored local environment and read-only version
attestation:

```powershell
$env:GARAGE_MULTIPART_PURGE_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o '-Dtest=MultipartPurgeIntegrationTest,GarageMultipartPurgeIntegrationTest,MultipartSessionIntegrationTest,MultipartCompletionIntegrationTest,MultipartCancellationIntegrationTest' test
```

Full suites must redirect every fixed/default schema to fresh private names, as in
M4. Never use shared public or retained regression schemas. Do not replay old
Garage journals.

Executed on 2026-10-18:

- Final focused M5 selection: **30 passed**, zero failures/errors/skips (26
  PostgreSQL lifecycle cases and four live Garage cases). Existing M1/M3/M4
  regressions also passed in the full run.
- Full safely isolated backend: **1,139 entries; 1,119 passed, 20 skipped**, zero
  failures/errors. Includes all seven live RabbitMQ cases and the continuous
  HTTP → outbox → broker → worker → Garage purge harness. Skips: 18 opt-in M0
  characterization cases, one reviewed-journal disposal case and the process
  recovery suite entry. Service reconstruction and transaction crash boundaries
  are tested; OS-process termination was not rerun for M5.
- Garage 2.3.0 was attested read-only over SSH. An accepted checksum-bound part
  returned **200 after READY DELETE**; exact-key GET remained absent in that
  interleaving. A permitted Complete finished after logical folder removal;
  positive evidence committed, READY stayed blocked and the completed object
  remained available to the verifier. A lost real abort 204 remained application
  UNKNOWN while name reuse and logical folder finalization succeeded without
  another storage call. Old deletion recovery preserved exact newer same-name bytes.
- Full verification used `backend/target/m5-full-af83af99`; all **169** production
  source/resource hashes matched the checkout. Fixed/default test schemas alone
  were redirected to twelve private names in its ignored `schema-map.json`.
  Exactly those twelve schemas were removed, with zero remaining under that
  task prefix. Successful random-schema suites disposed their own resources.
- All five full-run multipart journals and both successful focused M5 journals
  ended with `retained=0`. One earlier fixture assertion incorrectly expected M0
  metadata on production-created objects and interrupted an accepted part stream.
  Its journal `7afb98bd-846f-40b7-8541-261fb497a124` deliberately retains **one**
  allocation and schema `multipart_purge_live_44f7eb8a095a4d9ba2daf874e4c0db16`.
  No abort, DELETE or journal replay was used to resolve that stream's uncertainty.
  The assertion was corrected and subsequent runs passed. The pre-existing
  retained M0 allocation, shared public schema and retained regression schemas
  were never cleanup targets.
- Live broker checks used only the existing private test vhost and a task-owned
  SSH tunnel, closed afterward. The continuous harness used its documented
  `ASYNC_E2E_ALIGN_INITIAL_DUE=true` fixture option for existing clock skew; no clock,
  service configuration, credentials or autonomous defaults changed. No physical
  GC, replication, multi-node, browser, load or performance guarantee follows.
