# Sharing M3: delegated multipart uploads

Implemented under the [approved sharing design](sharing-permissions-design.md).
This extends [M1 grants](sharing-m1.md), [M2 Editor mutations](sharing-m2.md)
and the existing multipart lifecycle. [M4](sharing-m4.md) subsequently adds secure
file Viewer links; [Sharing M5](sharing-m5.md) adds permission activity. M6 remains planned.

## API and ownership

An Editor can reserve a multipart upload into an effectively editable folder.
The server derives the owner from that folder and atomically creates the file,
storage allocation and session for that owner. Storage keys use the owner's UUID.
The authenticated actor becomes the immutable session initiator. Null destinations
always reserve in the actor's own root. Files gain no independent creator grant.
Folder Editor capabilities now include `UPLOAD`; synchronous upload stays owner-only.
Existing positive-size, five-GiB, fixed-part geometry and SHA-256 restrictions remain.

Session responses add `ownerId` and `initiatorUserId`. These fields cannot select
ownership or grant authority through a request. All existing session routes accept
the owner or the original initiator with current Editor access to the original
destination: get, initiate, part URLs/refresh, part listing, complete/recover,
cancel/recover and cancellation status. Other Editors, Viewers, strangers and
revoked/downgraded initiators receive the same session 404. Missing JWT gives 401.
Reservation into a visible Viewer folder gives 403; an inaccessible folder gives
404. Existing 200/202 progress, 409 conflicts and sanitized errors remain.
Cancellation still cannot abandon a frozen completion decision or delete READY.
Ordinary completed-file access follows current location/grants without creator authority.

## Migration and replay

V33 adds non-null immutable `upload_sessions.initiator_user_id`, backfilled from
`owner_id`. The migration temporarily disables only the session identity trigger,
flushes the deferred session/allocation pair checks before further table changes,
then restores the guard. Its whole-row comparison includes the new identity.
No fingerprint, file, allocation, deadline, manifest, receipt, decision or dispatch
identity is rewritten. Like retained owner identity, initiator identity has no user
deletion cascade or user foreign key; evidence can outlive accounts and resources.

PostgreSQL uniqueness becomes `(owner_id, initiator_user_id, idempotency_key)`.
Reservation retains the existing owner/key advisory lock, so different initiators
with the same key conservatively serialize but receive independent sessions.
Destination owner is included in new request fingerprints. Existing owner-only
fingerprints remain replayable without rewriting retained evidence. Every replay
reauthorizes current destination and session lifecycle, preserving original expiry.
Different initiators still contend for the same owner/name namespace; the existing
database uniqueness constraint remains authoritative.

## Coordination and revocation

Only immutable destination/session owner identity is read before coordination.
Lock order remains shared owner hierarchy, fresh file, allocation, session, ordered
parts and completion/cancellation progress/receipts. Reservation's owner/key lock
precedes creation of resource rows. Grant changes and folder structural changes
take the matching exclusive owner hierarchy lock. Fresh scalar permission reads
run after coordination; neither JPA caches nor actor hierarchy locks supply authority.
No Garage I/O or signing runs inside a database transaction or while holding locks.

Fresh caller checks guard reservation/replay, initiation dispatch, registration,
listing, completion observation/freeze, separate completion dispatch, recovery
probe, cancellation and separate abort dispatch. Revocation or downgrade winning
before any admission denies new authority. A frozen manifest remains retained if
revocation wins before dispatch; the owner may recover within lifecycle rules.
Regrant permits resumption of unchanged eligible sessions without renewing expiry.

Once a dispatch permit commits, that single admitted call may finish. Trusted
backend result recording uses the retained owner identity and preserves exact
upload IDs, completion receipts and abort acknowledgements despite actor revocation.
READY separately validates the original lifecycle and existing receipt/CAS guards.
Public responses after storage work recheck caller authority outside uncertainty
catch blocks. Signing also rechecks OPEN state, session revision and expiry before
returning URLs; listing rechecks these after its storage observation.

Owner recovery retains existing blocked-progress and expired positive-evidence
semantics. Trash/purge barriers, storage identity fencing and completion versus
abandonment exclusion remain. No uncertain Create, Complete or Abort is redispatched.
Revocation cannot recall already issued Garage URLs. URL expiry, abort acknowledgement
and missing objects never prove physical reclamation or writer quiescence.

## Verification

`DelegatedMultipartIntegrationTest` uses real PostgreSQL and controlled storage:
all-route role/membership/authentication checks; owner-owned allocations/keys;
independent initiator/owner idempotency; namespace conflicts; immutability;
legacy fingerprint replay; regrant; inheritance/moves; trash/purge; owner recovery;
completion/cancellation exclusion; uncertain calls; response denial after signing,
listing, Create, Complete, recovery HEAD and Abort. Latch-controlled races observe
actual PostgreSQL waits for both revocation/downgrade winner orders at reservation,
initiation, parts, listing, URL response, freeze, completion dispatch, probe,
cancellation and abort.
The populated V32 upgrade compares exact retained rows across six session states.

The opt-in `GarageMultipartCompletionIntegrationTest` adds a disposable delegated
case using real signed UploadPart requests. It checks outstanding URLs after
revocation, denied new Editor completion, owner completion, receipt and exact bytes.
Existing multipart, sharing, lifecycle, storage and concurrency tests remain.

Verification commands load `backend/.env.local.ps1` in the Maven shell. The full
suite uses `vaultdrive_test.sharing_m3_final_verification` with:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m3_final_verification
-Dspring.flyway.schemas=sharing_m3_final_verification
-Dspring.flyway.default-schema=sharing_m3_final_verification
```

Explicitly isolated suites retain their schemas. The pre-existing default-schema
V26 checksum mismatch is not repaired. Application async defaults stay disabled;
RabbitMQ integration uses only the documented localhost tunnel and isolated
`vaultdrive_outbox_test` vhost. Live multipart fixtures journal disposable resources
and retain uncertain outcomes according to their existing gates.

The full suite on 2026-10-19 passed: **1,250 tests, 1,202 passed, 48 skipped,
zero failures/errors**. This includes all 42 delegated integration cases, existing
PostgreSQL sharing/lifecycle/concurrency checks, Garage storage and isolated
RabbitMQ integration. The focused delegated/live case run passed 42 tests; the
subsequent ten admission/response race cases also passed before the full run.

The supplemental Garage command ran **27 tests, all passed, no skips/errors**:
`GarageMultipartTransferIntegrationTest`, `GarageMultipartCompletionIntegrationTest`,
`GarageMultipartCompletionEvidenceTest`, `GarageMultipartCancellationIntegrationTest`
and `GarageMultipartPurgeIntegrationTest`. It enabled their four documented
`GARAGE_MULTIPART_*_TEST=true` gates and `GARAGE_MULTIPART_VERSION=2.3.0`, after
inspecting the VM image `dxflrs/garage:v2.3.0`. This includes the new delegated
signed-URL case and existing checksum, uncertain outcome, recovery, cancellation
and purge regressions. `git diff --check` and local Markdown links passed.

Of the 48 default-suite skips, 27 passed in that supplemental run. The remaining
21 checks were not executed:

| Suite | Not run | Gate |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` |
| `GarageMultipartJournalRecoveryTest` | 1 | reviewed `GARAGE_MULTIPART_REVIEWED_RUN` UUID |

Those harnesses retain their separate resource/protocol review requirements.
Existing browser, HTTPS/CORS, clock-skew, late-writer and physical reclamation limits remain.
Sharing M4-M6, zero-byte protocol changes and legacy upload retirement are outside M3.
M4 implementation and verification are recorded separately in [its contract](sharing-m4.md).
