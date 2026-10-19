# Sharing M1: grants and Viewer access

Implemented on 2026-10-19 under the [approved design](sharing-permissions-design.md).
This records the M1 boundary, where `EDITOR` initially permitted the same reads as
`VIEWER`. [M2](sharing-m2.md) now adds Editor folder creation, rename and conditional
trash. [M3](sharing-m3.md) adds delegated multipart; synchronous upload stays
owner-only. Links, activity events, notifications and frontend changes remain planned.

## Schema and resolution

V32 adds `resource_grants`, the file `(id, owner_id)` unique constraint, composite
target/owner foreign keys with target-deletion cascades, exactly-one-target and
non-self checks, registered-recipient FK, Viewer/Editor role check, partial unique
target/recipient indexes, and a recipient-first index. A trigger protects grant
identity and requires each update to advance its optimistic version by one.
Revocation deletes a direct grant; regranting creates a new UUID. Cascades affect
grants only; retained allocations, sessions, purge manifests and receipts retain
their existing contracts. ACL writes do not change file versions or timestamps.

`resolve_resource_access` is a bounded recursive PostgreSQL statement. It binds
current resource metadata/location, complete owner-consistent ancestry, lifecycle
and effective permission to one statement snapshot. Missing ancestry, cycles,
more than 256 ancestor folders, pending purge, deleted ancestors and non-READY
files fail closed. Owner comes from immutable ownership; otherwise the strongest
direct or ancestor-folder grant applies. No descendant grants are copied.

A directly shared resource can be read without access to its parent. Its parent
ID is returned as null unless the caller can access that parent. Shared entry
points expose neither private siblings nor the owner's root namespace. Public
resource DTOs contain `effectiveRole` and `allowedCapabilities`; storage keys and
private ancestry are internal. Capabilities describe role privileges; existing
lifecycle/operation eligibility checks still apply.

## API

All routes require JWT authentication; the JWT subject is the actor, never a
client-selected owner.

| Route | M1 contract |
|---|---|
| `POST /api/v1/{files\|folders}/{id}/grants` | Owner only; exact registered `email`, `role: VIEWER\|EDITOR`; 201, grant body and version ETag |
| `GET /api/v1/{files\|folders}/{id}/grants` | Owner only; `{direct, inherited}` lists with source IDs and recipient identity |
| `PATCH .../grants/{grantId}` | Owner only; role body and quoted version `If-Match: "0"`; 200 and updated ETag |
| `DELETE .../grants/{grantId}` | Owner only; 204; only matching direct target grants are removed; absent/repeated/wrong-target IDs are no-ops |
| `GET /api/v1/files/{id}` | Active READY metadata for owner or collaborator |
| `GET /api/v1/folders/{id}` | Active folder metadata for owner or collaborator |
| `GET /api/v1/folders/{id}/children` | Immediate accessible folders, deterministic case-insensitive name/name/UUID order |
| `GET /api/v1/files?folderId={id}&page=0&size=50` | Immediate accessible READY files; name/UUID order |
| `GET /api/v1/files/{id}/download` | Current READY authorization, then backend streaming through `ObjectStorageService`; attachment and no-store |
| `GET /api/v1/shared-with-me?page=0&size=50` | Directly granted accessible entry points, strongest effective role, redacted resource DTOs |

Shared pages contain `{content: [{resourceType: FILE|FOLDER, resource: ...}], page,
size, totalElements, totalPages}`. Authorization is filtered before sorting,
counting and pagination, including empty/out-of-range pages. Sizes are 1-100 and
page is nonnegative. Root file/folder listings and Trash remain scoped to the actor.

Grant target authorization precedes exact recipient lookup. Unknown or inaccessible
resources return 404; visible collaborators lacking owner privileges receive 403.
Missing If-Match returns 428, stale versions 412, malformed versions/roles or invalid
recipients 400, duplicate grants 409. A no-op role update retains its version.
Inherited grants must be changed at their source.

## Lifecycle and concurrency

Only immutable owner identity may be read before coordination. Grant mutations
acquire **exclusive owner hierarchy -> fresh scalar resource/ancestry/grants**.
Owner grant listing and resource reads acquire the shared owner hierarchy lock.
Existing mutations retain ADR-0007's hierarchy/row/allocation/session/job ordering.
Across owners, Shared with me uses one statement snapshot rather than holding
multiple owner locks.

Shared hierarchy locks permit same-file moves and reads to overlap. The resolver's
single statement snapshot prevents combining an old location/grant with new file
metadata. Folder changes and grant mutations serialize against those reads.
Cached JPA entities are not used for permission decisions.

Trash suspends both direct and inherited access. Owners may inspect, revoke or
reduce grants while trashed before pending purge. Creation and role increases
require an active accessible target. Restore reactivates retained direct grants;
inheritance follows the restored location, including root fallback. Moves preserve
direct grants and recompute inheritance for descendants. Pending purge blocks grant
management and collaborator access without weakening subtree barriers.

The metadata transaction commits before opening object storage or streaming. The
download service rejects ambient transactions. Already admitted downloads may
finish after revocation; later requests must resolve permission again. No storage
keys, upload identities, receipt rules, cleanup decisions or worker defaults change.

## Verification and limits

`SharingIntegrationTest` covers real PostgreSQL constraints, strongest roles,
redaction, listings/pagination, owner-only APIs, optimistic preconditions, lifecycle,
cycles/depth limits and target-grant cascades. The old repository-mocked read tests
are replaced by these database checks and small service-delegation tests.
`SharingConcurrencyIntegrationTest` forces both winner orders for grant/trash,
grant/file-move, revoke/read, revoke/purge and revoke/restore, plus duplicate grants,
stale role updates, compatible file move/read snapshots and cached-entity rejection.
It also downloads disposable Garage bytes as a Viewer and verifies revocation
preserves owner bytes. `SharingDownloadIntegrationTest` verifies no transaction or
hierarchy lock spans controlled storage I/O and an admitted stream may finish.

The existing default test schema has a pre-existing V26 checksum mismatch
(applied `1493870529`, repository `-713442801`). Verification uses the fresh
`sharing_m1_verification` schema in `vaultdrive_test`; no repair or applied-migration
edit is performed. The focused/full commands add these Maven arguments after
loading `backend/.env.local.ps1` in the same shell:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m1_verification
-Dspring.flyway.schemas=sharing_m1_verification
-Dspring.flyway.default-schema=sharing_m1_verification
```

Suites with explicit isolated-schema properties retain those schemas. Application
publisher/consumer/worker/reconciliation defaults remain disabled. The configured
isolated RabbitMQ tests ran using the [documented localhost AMQP tunnel](async-purge-verification.md).

Verification on 2026-10-19: **197 focused tests passed**; the final full backend
suite ran **1,162 tests: 1,115 passed, 47 skipped, zero failures/errors**. Real
PostgreSQL, Garage Viewer download/storage/purge regressions and isolated RabbitMQ
publisher/consumer tests passed. An earlier full attempt had four broker connection
errors before the tunnel was opened; the final complete rerun passed.

The 47 skips are explicitly opt-in checks, not evidence of passing those scenarios:

| Suite | Skipped |
|---|---:|
| `AsyncPurgeEndToEndTest` | 1 |
| `AsyncPurgeProcessRecoveryTest` | 1 |
| `GarageMultipartCancellationIntegrationTest` | 7 |
| `GarageMultipartCapabilityTest` | 18 |
| `GarageMultipartCompletionEvidenceTest` | 1 |
| `GarageMultipartCompletionIntegrationTest` | 10 |
| `GarageMultipartJournalRecoveryTest` | 1 |
| `GarageMultipartPurgeIntegrationTest` | 4 |
| `GarageMultipartTransferIntegrationTest` | 4 |

No performance or production-scale claim is made: recursive per-entry resolution,
materialized page filtering and offset pagination have not been benchmarked.
M3-M6 and existing multipart browser, clock and uncertain-writer limits remain.
M2 verification is recorded separately in [its contract](sharing-m2.md).
