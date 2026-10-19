# Sharing M2: Editor content operations

Implemented on 2026-10-19 under the [approved matrix](sharing-permissions-design.md).
M2 extends [M1](sharing-m1.md); M3-M6 remain planned.

## Behavior and ownership

- `POST /api/v1/folders`: Editors create under an effectively editable folder.
  The server derives the destination owner; the new folder belongs to that owner,
  inherits current grants and receives no independent creator grant. A null parent
  always creates in the authenticated actor's own root.
- File/folder rename accepts effective Editor on the target, including a direct
  grant without parent access. Normalization, extension preservation, no-op behavior
  and owner namespace uniqueness remain.
- File/folder trash requires Editor on the target's immediate parent. Null/root,
  private or Viewer-only parents yield 403 for visible Editors. Inherited Editor
  can manage content created by the owner or another Editor.
- Move, restore, permanent deletion, grant management, legacy upload and multipart
  remain owner-only. Root listings and Trash expose only the actor's content.
  Renaming a directly shared root entry remains allowed; trashing it does not.

Viewer mutations return 403. Strangers and inaccessible/lifecycle-blocked resources
return 404; missing authentication returns 401. Owner-only operations on active
visible collaborators return 403; trash suspends visibility, so collaborator
restore/purge requests on trashed content return 404. Mutation responses preserve
private-parent redaction and effective role. `allowedCapabilities` describes role
privileges: Editor gains `CREATE_FOLDER` (folders), `RENAME` and conditional `TRASH`,
without `UPLOAD`; lifecycle and immediate-parent eligibility still apply.

## Coordination and lifecycle

Only immutable owner identity is read before hierarchy coordination. The JWT
subject remains the actor in changed controllers and service paths. Lock order
remains resource-owner hierarchy (exclusive for folders, shared for files), fresh
resource/ancestry/permission state, then existing writes. There is no actor-hierarchy
lock or shared-to-exclusive upgrade. Grant mutation uses the existing exclusive
owner lock. Whichever transaction commits first determines Editor authorization.

The resolver evaluates current complete ancestry, strongest direct/inherited role,
READY state and trash/pending-purge barriers inside the mutation transaction.
Folder targets are refreshed under exclusive coordination before mutation.
File targets are refreshed before scalar authorization, retaining their optimistic
version. Concurrent same-file moves/writes under shared hierarchy locks produce
one writer and a stale-write conflict. Collaborator mutation responses use the
authorized snapshot's visible parent, including no-op renames.

Owner subtree moves recompute inheritance without copying grants. Direct Editor
grants survive moves but do not authorize trash in a private destination. Ancestor
trash suspends collaborator operations; pending purge remains a subtree barrier.
Owner restore retains grant/reactivation behavior. No migration, folder version
column, storage identity, object I/O, multipart session, receipt, dispatch or upload
lifecycle change is introduced. Folder serialization and file optimistic versions
retain their existing contracts.

## Verification

`SharingIntegrationTest` covers Owner, Viewer, Editor, stranger and unauthenticated
requests; direct/inherited roles, owner-assigned creation, creator revocation,
rename/no-op redaction, extension/version preservation, immediate-parent trash,
root boundaries, owner-only operations, upload rejection, namespace conflicts,
non-READY files, subtree moves and trash/purge barriers.
`SharingConcurrencyIntegrationTest` observes actual PostgreSQL advisory/row waits
and checks both revocation/downgrade orders across all five Editor operations,
direct rename grants, owner subtree move/trash, stale cached entities and same-file
Editor rename/trash versus owner move. Existing hierarchy, download, lifecycle,
storage and multipart suites are retained.

Verification uses `vaultdrive_test.sharing_m2_verification`, created fresh for M2,
with UUID-scoped committed fixtures cleaned by concurrency tests and rolled-back
API fixtures. Existing explicitly isolated suites retain their schemas. The default
test schema's pre-existing V26 checksum mismatch is not repaired and no applied
migration is edited. Load `backend/.env.local.ps1` in the Maven shell and add:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m2_verification
-Dspring.flyway.schemas=sharing_m2_verification
-Dspring.flyway.default-schema=sharing_m2_verification
```

Application worker/publisher/consumer/reconciliation defaults remain disabled.
Isolated RabbitMQ tests use only `vaultdrive_outbox_test` through the documented
[localhost AMQP tunnel](async-purge-verification.md). Garage tests use disposable keys.

Verification on 2026-10-19:

- Focused command: `-Dtest=SharingIntegrationTest,SharingConcurrencyIntegrationTest,SharingDownloadIntegrationTest,FolderServiceTest,FileServiceTest,FileMetadataServiceTest,FileHierarchyConcurrencyIntegrationTest` with the schema arguments above: **213 passed**, no failures/errors/skips.
- Full `mvnw.cmd test` with those schema arguments and `RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`: **1,207 tests, 1,160 passed, 47 skipped**, no failures/errors. PostgreSQL, Garage storage/download/purge, multipart metadata/lifecycle and isolated RabbitMQ tests passed.
- `git diff --check` and local Markdown link validation passed.

The skipped checks retain their explicit gates; these scenarios were not verified
by this run:

| Suite | Skipped | Required opt-in |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` |
| `GarageMultipartCancellationIntegrationTest` | 7 | `GARAGE_MULTIPART_CANCELLATION_TEST=true` |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` |
| `GarageMultipartCompletionEvidenceTest` | 1 | `GARAGE_MULTIPART_COMPLETION_TEST=true` |
| `GarageMultipartCompletionIntegrationTest` | 10 | `GARAGE_MULTIPART_COMPLETION_TEST=true` |
| `GarageMultipartJournalRecoveryTest` | 1 | `GARAGE_MULTIPART_REVIEWED_RUN` UUID |
| `GarageMultipartPurgeIntegrationTest` | 4 | `GARAGE_MULTIPART_PURGE_TEST=true` |
| `GarageMultipartTransferIntegrationTest` | 4 | `GARAGE_MULTIPART_TRANSFER_TEST=true` |

Opt-in environment variables alone do not establish the reviewed resource,
protocol and infrastructure prerequisites documented by those harnesses.

## Remaining limits

Delegated multipart, legacy upload extensions, links, activity, notifications and
frontend work are outside M2. Existing multipart browser, clock and uncertain-writer
limits remain. No performance or production-scale claim is made. Stop at M2.
