# Multipart cancellation and expiry — M4

Current authorization extension: [Sharing M3](sharing-m3.md) adds owner/initiator
separation and delegated Editor sessions, superseding owner-only authorization
statements in this historical milestone record. Storage/lifecycle guarantees remain.

Current lifecycle extension: [M5 purge compatibility](multipart-purge-compatibility.md)
supersedes the historical blanket multipart purge fence and retained active-name
reservation described in this milestone record. It separates logical disposal
from completed-object deletion and unresolved physical reclamation.

M4 extends [M1 identity](multipart-upload-sessions.md), [M2A transfers](multipart-part-transfers.md)
and [M3 completion](multipart-completion-recovery.md). Cancellation and expiry are
permanent authorization decisions. An abort acknowledgement supplies no proof of
writer termination, physical reclamation or file-removal eligibility. Legacy
uploads, existing purge proofs and autonomous processing defaults are unchanged.

## API

Every public operation requires owner JWT authentication and fresh file/allocation/
ancestor eligibility. Unknown, foreign and inaccessible sessions return the same
404; missing/invalid JWT returns 401. No request body is required.

| Request under `/api/v1/upload-sessions` | Behavior |
|---|---|
| `POST /{id}/cancel` | Atomically choose abandonment and intent; grant at most the first eligible abort permission; otherwise return retained progress. |
| `POST /{id}/cancel/recover` | Require existing M4 intent; advance only an undispatched eligible cancellation. Never redispatch an existing permission. |
| `GET /{id}/cancellation` | Read retained progress without storage I/O; 409 if no M4 intent exists. |

POST returns **200** for durable `CANCELLED`, **202** for PENDING/UNKNOWN/BLOCKED,
and **409** for a completion decision, including READY. Location points to the
cancellation GET. The response contains `session`, `cause`, `disposition`, `reason`,
`dispatchId`, `revision`, `evidenceKind` and `acknowledgedAt`. Cancellation GET
returns 200 with those same fields. Existing session GET and completion/recovery
contracts are unchanged.

Compatible repeats retain the original decision, cause, dispatch UUID and evidence
timestamp. Cancel may adopt an old M1 abandonment without changing its decision;
recovery alone cannot adopt it. Cancel after expiry retains cause EXPIRED.
`CANCELLED` means either initiation was permanently precluded (`NEVER_INITIATED`),
or the backend's exact abort returned HTTP 204 and that fact committed (`ABORT_ACK`).
It does not mean all part handlers ended or all bytes were reclaimed.

## V30 and transitions

[V30](../backend/src/main/resources/db/migration/V30__multipart_cancellation.sql)
adds only `multipart_cancellations`; V25–V29 are unchanged. The row binds the
immutable session/decision, initiation/upload ID, full storage identity and cause
(CANCEL or EXPIRED). Dispatch identity/time are single-assignment; acknowledged
evidence is terminal and retained. PostgreSQL generates timestamps and guards revisions.

```text
RESERVED / INITIATING / OPEN -> ABANDONMENT_DECIDED (permanent)
OPEN -> COMPLETION_DECIDED (permanent, mutually exclusive)

cancellation: PENDING -> UNKNOWN (one dispatch permission) -> CANCELLED (204 recorded)
                     -> BLOCKED (no permission yet)
              BLOCKED -> UNKNOWN (first permission only, blocker resolved)
              PENDING -> CANCELLED (NEVER_INITIATED only)
```

A dispatch followed by timeout, connection failure, NoSuchUpload, unexpected
response or acknowledgement-commit uncertainty retains UNKNOWN. Recovery returns
that state without Abort, Complete, HEAD, ListParts or DELETE. M4 has no provider
evidence path that safely resolves a lost abort acknowledgement. Elapsed time and
negative observations never grant another dispatch permission.

| Last durable boundary | Explicit recovery behavior |
|---|---|
| Intent only, no dispatch | Revalidate and grant at most the first permission. |
| Dispatch transaction rolled back | Retain PENDING/BLOCKED; a later first permission is possible. |
| Permission committed, its acknowledgement lost | No ephemeral permission reaches the caller; retain UNKNOWN and never send again. |
| Permission committed, crash before sending | Retain UNKNOWN even if no request actually reached Garage. |
| Abort response lost, or 204 received but acknowledgement commit fails | Retain UNKNOWN; no absence probe or repeated abort. |
| Acknowledgement committed, response lost | Return the same CANCELLED evidence and timestamp without Garage I/O. |

INITIATING abandonment without a bound ID remains BLOCKED / UPLOAD_ID_UNKNOWN,
even if Create later returns. No late identity adoption or repeated Create is
introduced. Old OPEN sessions without V28 initiation dispatch evidence remain
BLOCKED / INITIATION_UNVERIFIED. Configuration drift yields IDENTITY_MISMATCH;
stored endpoints never construct alternative clients.

## Locks, capabilities and folder barriers

The order is shared owner hierarchy → fresh scalar file FOR UPDATE → allocation
FOR UPDATE → session FOR UPDATE → cancellation. Only immutable owner/file identity
precedes coordination. The session decision and intent commit together. Completion,
cancellation and expiry contend on the same file/session rows. No transaction or
hierarchy lock spans signing or Garage I/O; orchestration rejects ambient transactions.
The existing SDK uses one attempt with finite call/attempt timeouts.

New part authorization requires OPEN and PostgreSQL-unexpired eligibility. A final
check after signing rejects batches if abandonment, completion, expiry or a folder
barrier won during signing. That check is the response-authorization linearization
point; an already authorized response can still be in transit after cancellation.
PostgreSQL cannot revoke an issued signature, and accepted handlers may continue
after abort acknowledgement.

New public decisions/recovery respect inaccessible ancestry. An undispatched
intent blocked by later folder trash/purge receives no permission. Restore may
allow its first dispatch after fresh checks. Permission committed before folder
mutation may finish outside locks; its exact acknowledgement is recorded despite
the new barrier. Files, versions, original destinations and folder manifests stay
unchanged. Every multipart physical purge fence remains, even for acknowledged or
never-initiated cancellation.

## Explicit expiry

Call `MultipartCancellationService.expire(afterUuid, limit)` from an explicit
internal operator/test context. Start with null and a small limit such as 20;
valid limits are 1–100. There is no expiry HTTP endpoint, scheduler, queue or worker.
No autonomous enablement switch is added.

The scan reads a bounded UUID keyset of undecided sessions with `expires_at <=
clock_timestamp()`. Each candidate has an independent bounded transaction that
rechecks PostgreSQL time, lifecycle, immutable identity and ancestry **after locks**.
It atomically chooses abandonment and EXPIRED intent. RESERVED can finish
NEVER_INITIATED; OPEN remains PENDING. Expiry performs zero Garage calls, including
zero first aborts. Explicit cancel/recover may later grant a first eligible abort.

The page returns `results:[{id,disposition,reason}]` and `nextCursor`. Advance a
nonnull cursor past blockers too; a full last page may require an empty next call.
Concurrent decisions return SKIPPED / DECISION_EXISTS, nonexpired candidates
SKIPPED / NOT_EXPIRED, barriers BLOCKED / LIFECYCLE_BLOCKED and transaction failures
BLOCKED / DATABASE_UNAVAILABLE. Scan failure propagates without inventing a page.
Restart at null on a later intentional pass to revisit blockers; the cursor is not
a permanent completion watermark. Completion decisions (including undispatched or
uncertain completion) and READY files are never expired. Database expiry already
stops URL authorization before this operation runs.

## Recovery and retention runbook

1. Read cancellation progress with the owner JWT. Inaccessible-folder 404 does not
   mean identity disappeared; internal retained rows and expiry results expose blockers.
2. For PENDING/BLOCKED without dispatch, resolve only the reported accessibility or
   original-binding blocker, then explicitly recover. Unknown initiation has no
   safe identity-repair path in M4.
3. For UNKNOWN with dispatch, retain allocation/session/progress and storage identity.
   Do not clear dispatch, age it into retry, repeat abort, infer success from
   NoSuchUpload/HEAD absence or delete a completed key as fallback. No manual repair
   endpoint is supplied.
4. For CANCELLED, retain evidence and allocation. It cannot feed legacy cleanup,
   folder purge or physical-removal proof. Metadata remains UPLOADING and its
   active name remains reserved; releasing/removing it requires a later lifecycle/
   proof extension. Pending folder purge may remain blocked indefinitely.

## Verification and limits

[PostgreSQL/API tests](../backend/src/test/java/com/vaultdrive/file/MultipartCancellationIntegrationTest.java)
use fresh schemas, committed fixtures, independent transactions, bounded latches
and observed `pg_blocking_pids` waits. They cover both winner orders, signing and
authorization races, folder barriers, crash/restart, drift, owner isolation,
idempotency and database guards. [Live tests](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartCancellationIntegrationTest.java)
use production adapters and individually journalled disposable allocations with a
readable unrelated control. A test-only verifier observes the dropped 204 for
fixture disposition; it grants the application no recovery authority. No old
journal or retained M0 allocation is replayed.

From `backend/`, load the ignored environment and attest Garage 2.3.0 read-only:

```powershell
$env:GARAGE_MULTIPART_CANCELLATION_TEST = 'true'
$env:GARAGE_MULTIPART_COMPLETION_TEST = 'true'
$env:GARAGE_MULTIPART_TRANSFER_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o '-Dtest=MultipartCancellationIntegrationTest,MultipartCompletionIntegrationTest,MultipartTransferIntegrationTest,MultipartSessionIntegrationTest,GarageMultipartCancellationIntegrationTest,GarageMultipartCompletionEvidenceTest,GarageMultipartCompletionIntegrationTest,GarageMultipartTransferIntegrationTest,S3MultipartStorageTest,S3ObjectStorageServiceTest,SinglePutDispatchIntegrationTest,StaleUploadExpiryIntegrationTest,UploadFinalizationRecoveryIntegrationTest,KnownSuccessCleanupIntegrationTest' test
```

Full runs must redirect fixed/default test schemas to fresh private names as in
preceding milestones. Crash tests reconstruct services at committed boundaries;
they do not kill an OS process. No physical reclamation, multi-node/replication/GC,
browser, load or bounded clock-skew claim is established. Legacy retirement,
frontend work and multipart removal proofs remain outside M4.

Executed on 2026-10-18:

- Combined focused selection: **347 passed**, zero failures/errors/skips. After
  permission-commit response handling was hardened, the final cancellation-only
  selection passed **46 cases**, including durable claim acknowledgement loss,
  rollback, expiry batch continuation and pending-purge barriers.
- Final full safely isolated backend suite: **1,109 entries, 1,089 passed,
  20 skipped**, zero failures/errors. This includes all seven live broker cases,
  the continuous HTTP → outbox → RabbitMQ → job → Garage scenario and M3/legacy
  regressions. Skips are 18 separately opt-in M0 characterization cases, the
  reviewed-journal disposal case and the process-recovery suite entry.
- Garage 2.3.0 returned 204 for production abort before/after partial upload;
  a fresh one-byte part returned 404 afterward. A held accepted checksum-bound
  three-MiB part returned **200 after the abort acknowledgement**. No physical
  reclamation or request-quiescence claim follows.
- The TCP proxy consumed an actual Garage 204 and dropped the entire downstream
  response. Application progress stayed UNKNOWN through duplicate/recovery calls,
  without a second abort, HEAD/ListParts probe or object DELETE. A real abort
  followed by failed database acknowledgement also stayed UNKNOWN. Existing READY
  bytes stayed exact and could neither be cancelled nor expired.

The final full run used `backend/target/m4-full-d8fe8213`, with all 168 production
source/resource hashes matching the checkout. Only test schema literals/default
profile were redirected to twelve fresh names in its ignored `schema-map.json`.
An earlier pre-hardening full run also passed (1,104 entries, 20 skips). Exactly
those two runs' **24** fixed/default schemas were removed, with zero remaining
under either task prefix. Random per-suite schemas disposed themselves. All **16**
new multipart journals reported `retained=0`; shared public data, retained
regression schemas, unrelated objects and the retained ambiguous M0 allocation
were never cleanup targets. The verifier's known abort acknowledgements resolve
test ownership only; application UNKNOWN rows acquired no repair authority.

Live broker checks used the existing isolated `vaultdrive_outbox_test` vhost over
task-owned local SSH forwarding, closed after verification. The continuous harness
used the documented `ASYNC_E2E_ALIGN_INITIAL_DUE=true` fixture option for clock
skew; this tests execution after initial eligibility, not production scheduling
under skew. No infrastructure, clock, credentials or autonomous defaults changed.
