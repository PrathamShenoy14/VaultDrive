# Full backend code and architecture consolidation

Current zero-byte extension: [ADR-0016](decisions/0016-zero-byte-files.md) permits
only empty-body SERVER_PUT allocation/admission, one dispatch and positive
verification receipts. Direct multipart remains the nonempty protocol. The broad
synchronous upload retirement and historical inventory below describe consolidation.

Current operational extension: [MVP hardening](operational-hardening.md) implements
per-instance admission, dependency/worker readiness, bounded logical expiry and
receipt-backed finalization, deployment safeguards and audited operator recovery.
V45 adds the audit/guard extension; this historical consolidation inventory and its
deferred general-admission/readiness statements describe the earlier milestone.
Physical reclamation, browser, retention, backups and actual deployment remain gated.

Current API update: [MVP API contract consistency](api-contract.md) supersedes
historical capability, folder/Trash pagination, deletion-polling and error-envelope
descriptions below. Storage, authorization and worker protocols are unchanged.

Date: 2026-10-20. Scope: the complete Spring Boot backend, its tests, build,
configuration and V1–V37 migration history. No product feature, schema migration,
frontend, deployment or shared-data repair is included.

Final inventory: 160 production Java sources, 108 test/helper Java sources and 37 applied migration files.

## Acceptance and audit method

Keep one supported new-upload protocol: direct multipart. Remove synchronous
admission without losing the ability to interpret historical SERVER_PUT state.
Preserve ownership, revocation, transaction boundaries, advisory key derivation,
lock order, immutable dispatch permissions, receipts and deletion barriers.

The starting working tree was clean. The audit inventoried every production and
test package, controllers/routes, DTOs/entities, repositories, configuration,
dependencies and migrations. Call-site searches distinguished runtime callers,
test fixtures and explicit operational entry points. The transaction/storage and
security paths were traced against ADRs 0001–0009, the sharing M1–M6 contracts,
authentication sessions and browser interoperability record. Verification tests
establish the exercised behavior; this audit is not a load, penetration, failover
or live-browser certification.

## Findings and decisions

| Area and inspected responsibilities | Classification | Decision and evidence |
|---|---|---|
| `file/FileController`, `FileService`, `FileMetadataService`: synchronous upload orchestration and admission | Safe to remove | Remove POST `/api/v1/files`, MultipartFile handling, UPLOADING creation, foreground FAILED/READY transitions and failure wrapper. GET/PATCH/DELETE and restore routes remain. Authenticated POST now receives 405; anonymous POST remains 401. |
| `storage/FileStorageIdentityService`, `S3ObjectStorageService`, `ObjectStorageService`: SERVER_PUT allocation/admission and PUT | Safe to remove | Remove `reserve`, `beginWrite`, adapter `upload` and key-only `delete`; disable unused servlet multipart parsing. New allocations explicitly insert DIRECT_MULTIPART. All production DELETE callers supply a retained `StorageTarget`; no compatibility adapter or fallback. |
| `file/UploadFinalizationRecovery`, `UploadFinalizationTransactions`: foreground receipt creation/reload | Safe to remove | Remove `complete`, `recordSuccess`, `hasSuccess` and their private identity matcher. Recovery cannot fabricate missing historical success evidence. |
| Upload response/exceptions and old single-PUT fault server/tests | Safe to remove | Remove `UploadFileResponse`, `FileUploadException`, `UploadFinalizationRejectedException`, their handler, `SinglePutDispatchIntegrationTest` and `ControlledPutServer`. Remove tests for deleted admissions/foreground races. Preserve the historical verification document with an explicit retirement note. |
| `StoredFileRepository`, `FolderRepository`: old owner-only active listings | Safe to remove | Remove superseded file/root and folder/root listing queries, unused file lookup and `findUploadingForStorageWrite`. Current resource reads use permission-aware resolver snapshots. Replace fixture inspection with existing repository reads; remove obsolete query-only tests. Current listing, ordering, pagination and authorization integration tests remain. |
| Purge and permission notification listeners | Safe to refactor | Their identical three-attempt retry, quarantine-before-ACK and stop-on-uncertainty transport policy now lives in package-private `NotificationDelivery`. Typed parsers, transactional ingestors, queues, routing and sanitized stop codes remain separate and unchanged. Existing listener, PostgreSQL and isolated RabbitMQ tests cover both uses. |
| Maven build metadata and dependencies | Safe to refactor | Replace empty generated project metadata. Remove duplicate `flyway-core` and `spring-security-crypto` declarations supplied by supported Boot/Flyway/security dependencies, verified from cached POMs and the clean build. Retain PostgreSQL Flyway support, JPA, validation, OAuth resource server, AMQP, AWS S3/Apache and test starters. No version upgrade. |
| `auth`, `security`, `user`: registration, BCrypt, JWT validation, refresh families, CSRF, profile | Retain current supported behavior | Constructor injection, explicit response records, secret hashing/redaction, family lock before fresh token state, commit-before-replay error and fail-closed revocation are intentional. Session JDBC has no parallel JPA session model. No authentication feature or token transport change. |
| `folder`, `file`, `hierarchy`: namespaces, Trash/restore, ownership, optimistic versions | Retain current supported behavior | Existing service transactions and constraints remain authoritative. Broad owner coordination is deliberate; no measured basis for a lock redesign. Entity status setters remain exercised by persisted-history and database-guard tests; they are not an upload admission service. |
| `sharing`: resolver, strongest-role inheritance, Editor mutations, links, activity, notifications | Retain current supported behavior | SQL is needed for one-statement authority/count/page snapshots and immutable evidence. Redacted DTOs and fresh mutation authorization remain. No alternate owner-only read path is introduced. |
| `outbox`, `job`, `consumer`, `worker`: claims, progress and purge | Retain current supported behavior | PostgreSQL is durable authority; RabbitMQ is an at-least-once notification channel. Polling recovers publication, execution and eligible missed ingestion; it is not a second business queue architecture. Existing inspection repositories are used by integration tests; internal recovery entry points are intentionally explicit, not dead scheduled work. |
| Historical SERVER_PUT allocations, V25 receipts, V24 expiry, V26 cleanup and purge proofs | Must retain for durable historical state | Retain inventory, never-admitted expiry, receipt-only READY recovery, restricted known-success cleanup and historical SUCCEEDED file-purge recovery. Keep protocol predicates and conservative blockers. No backfill from current binding, HEAD, age or missing metadata. |
| V1–V37 Flyway history, retained storage/session/dispatch/manifest/progress tables | Must retain for durable historical state | No migration edited, removed, repaired or rewritten. SERVER_PUT database defaults/functions remain historical schema compatibility, not an application entry point. Permanent key allocations and unknown multipart effects remain retained. |
| Historical test setup | Safe to refactor | Explicitly imported test-only `HistoricalUploadFixtures` seeds old metadata/admission/receipts through existing DB guards. `HistoricalObjectFixtures` writes only fresh disposable test keys outside transactions. No application component scans these as a production protocol. Process harness HTTP setup now uses direct multipart through `MultipartHttpUploads`. |
| Oversized services, repeated ancestry SQL and phase-oriented names | Separate bounded refactor when justified | Keep cohesive lock-sensitive transaction methods together. Similar ancestry queries have different eligibility/purge/expiry semantics; a generic query framework would obscure them. No mechanical package or persistence rewrite. Correct the stale discovery error claiming byte execution was unimplemented. |

Java 21 records remain the public DTO convention, JPA entities stay internal, and production services/configuration use constructor injection. The explicit constructor annotation on public-link admission selects its production constructor alongside its test ticker constructor. Package boundaries follow domain responsibilities; no unused production controller or alternate authentication/sharing stack was found. Existing exception status/body conventions are preserved beyond the removed upload exception; normalizing mixed error-label capitalization would be a separate API contract cleanup. Sanitized transport/storage failures and secret-redacted capabilities remain intact.

## Persistence rationale and boundaries

JPA/Hibernate owns managed user/folder/file metadata and immutable outbox insertion.
It gives ordinary CRUD, optimistic file versions and explicit pessimistic Trash-row
locks. Native EntityManager SQL owns PostgreSQL-specific resource authorization,
recursive ancestry, immutable evidence and conditional lifecycle/job transitions.
The hierarchy coordinator uses Hibernate's enlisted JDBC connection, not an
independent connection. JdbcTemplate owns refresh sessions and token revocation;
there is no managed session entity to become stale.

All use the configured datasource and Spring transaction manager. JPA and JDBC
are tools within these boundaries, not competing implementations of one operation.
Moving every SQL statement into new repository layers would add indirection without
changing authority or safety, so SQL remains in the existing responsibility's
transaction service or access repository.

`spring.jpa.open-in-view=false` is required. Mutation authorization uses fresh
scalar state after hierarchy coordination and explicitly refreshes managed
file/folder targets where required. Bulk file-status CAS flushes/clears the
persistence context. Resource/proof workers use fresh scalar row locks and reads;
job-only claim/outcome calls use independent transactions. Native updates must
never be followed by saving an older managed instance of the same row. Existing
OSIV controls, blocking races, CAS and rollback tests remain relevant; this
milestone changes neither lock keys nor protected-read order.

Folder structural/grant mutations take exclusive owner hierarchy coordination;
file operations take shared coordination and retain version or eligible-row
protection. Restore adds the destination namespace lock after its eligible file
row. Multipart uses hierarchy → file → allocation → session → decision/progress.
Purge checkpoints take resource/progress locks before the final job fence.
Authentication locks the refresh family before re-reading protected token state.
No broker or Garage I/O enters a metadata transaction. See
[ADR-0007](decisions/0007-concurrency-locking.md) for the exact existing orders.

## Retained operational recovery

| Entry point | Retained authority and limitation |
|---|---|
| `StaleUploadInventory.scanPage` | Read-only observations; classification never authorizes cleanup. |
| `StaleUploadExpiry.expirePage` | Only verified old never-admitted SERVER_PUT reservations; atomic preclusion/FAILED/expiry evidence. No Garage I/O. |
| `UploadFinalizationRecovery.recoverPage` | Original V25 receipts only; fresh ancestry/lifecycle/version checks. Missing receipt stays unknown; FAILED is never resurrected. |
| `KnownSuccessCleanup.cleanupPage` | Original receipt-backed FAILED history plus permanent V26 abandonment, bounded claim and exact retained target. No general orphan sweep. |
| `FilePurgeRecoveryService.recoverPage` | Matching historical SUCCEEDED job and immutable target/allocation; preserve original outcomes and receipts. |
| Multipart explicit recovery/cancellation | Original dispatch/manifest/identity and strict positive evidence. Unknown Create/Complete/Abort never grants redispatch. Logical disposal never proves physical reclamation. |
| Missing-job reconciler | Aged PUBLISHED, unquarantined matching intent/target/run only; no reset, broad historical backfill or storage effects. |

These paths remain useful after new SERVER_PUT admission stops. Their bounded
configuration is retained. Publisher, both consumers, worker and missing-job
poller remain independently disabled by default. No new scheduler or execution flag exists.

## Deferred decisions and risks

These items are classified as **Requires a separate migration or product decision**; none is silently repaired by this milestone.

- Retention/erasure of allocations, receipts, outbox/jobs/quarantine, refresh-token
  history, activity and notifications requires a data/product policy and a separate
  migration. Absence of runtime callers is not deletion authority for durable data.
- Unknown multipart initiation/completion/abort and late effects require an
  independently proven reclamation protocol. Garage acknowledgement, empty
  ListParts, URL expiry and age do not prove writer termination or GC.
- Unknown legacy bindings, pre-outbox/blocked work, missing receipts and historical
  V26 checksum drift are not repaired. Verification uses new isolated schemas.
- Retire old application binaries/writers during rollout. This removes new
  SERVER_PUT authority in the application; it is not a database privilege fence
  against a previously running binary or an external trusted writer.
- Direct multipart retains its positive-size/five-GiB/fixed-part policy. Retirement
  removes the old zero-byte upload route; any zero-byte protocol needs a product
  decision. No compatibility path is supplied.
- Live browser HTTPS/origin/CORS/clock verification remains open. JDK/Node tests
  do not close the browser release gate. Process-kill and retained-journal runs
  retain their independent explicit gates.
- Current authenticated downloads use the configured S3 client/bucket and opaque
  key. Supporting reads across deliberately changed historical bindings requires
  a separate binding/client policy; this milestone does not migrate identities.
- General rate limiting, JWT key evolution, quotas, backup/disaster recovery,
  health dependency probes, multi-instance admission and measured contention/
capacity targets remain separate work. NIO client deprecation and unchecked SQL
  projection warnings remain bounded technical debt; no performance claim is made.

## Verification

Final results and the exact full command follow below.
Initial focused historical-recovery/metadata/controller/adapter verification:
**234 tests, zero failures/errors/skips**. A later contract run found one obsolete
Editor assertion expecting 404 from the retired upload route; it was changed to
405. Build/fixture compilation corrections were completed before final verification.

Only new schemas in `vaultdrive_test`, per-test disposable Garage allocations and
UUID topology in `vaultdrive_outbox_test` are eligible. A temporary loopback-only
SSH AMQP forward reaches the existing broker. Read-only SSH container inspection
confirmed PostgreSQL 17, Garage 2.3.0 and RabbitMQ 4.3.6. No VM, bucket, clock,
existing schema checksum, durable allocation or production execution default was
changed. Uncertain fixture writes retain their evidence under existing test rules.


The first clean full attempt ran 1,311 tests with 89 failures and 21 gated skips.
One failure was an obsolete fixture exception expectation; 88 were cleanup prefix
assertions because the new historical-object helper initially used a different key
shape. The helper now uses the established opaque `users/{owner}/files/{file}`
shape. A bounded task-only disposal checked exact owners from those cleanup errors,
UUID key shape, seven-byte fixture size and this run's creation window; it removed
31 known completed fixture objects and verified absence. No multipart or historical
user allocation was selected. Corrected affected suites passed **156 tests with
zero failures/errors/skips**. The isolated HTTP direct-multipart ? outbox ? RabbitMQ
? file/folder purge/replay run plus isolation controls passed **3 tests**. Node
protocol/proxy checks passed **17 tests**, after rerunning outside the sandbox
because its child-process launcher returned EPERM; JavaScript syntax passed too.


Final clean full run completed at **2026-10-19 19:04:39 UTC**: **1,311 tests;
1,291 passed, 20 skipped, zero failures/errors**. It included all isolated RabbitMQ
checks, the four safe Garage multipart gates, the live continuous HTTP pipeline
and retained historical recovery/concurrency suites. The continuous nested-folder
run reached DISCOVER -> DELETE_FILES -> DELETE_FOLDERS -> FINALIZED, retained proofs,
replayed duplicate/redelivered notifications and kept controls readable; all seven
owned objects plus its private schema/queue/exchange were cleaned.

Exact final clean command from `backend/`, with the task-owned AMQP forward open:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
.\mvnw.cmd -o clean test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=backend_consolidation_final' '-Dspring.flyway.schemas=backend_consolidation_final' '-Dspring.flyway.default-schema=backend_consolidation_final'
```

Default contexts used `backend_consolidation_final`; suites with their own schema
isolation retained those patterns. Synthetic verification schemas remain retained.
The temporary task-owned SSH forward was closed after full verification. No
production execution default, shared checksum or storage identity was changed.

| Separately gated suite | Skipped | Gate intentionally left closed |
|---|---:|---|
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` and M0 characterization runbook |
| `GarageMultipartJournalRecoveryTest` | 1 | Separately reviewed retained-run UUID |

Live browser interoperability remains unverified. The process-kill matrix and
reviewed historical journals were not executed or adopted. Local Markdown file/
heading targets, stale production caller searches, runtime dependency-classpath
checks and whitespace checks passed. Runtime Flyway core, security core and BCrypt
remain present; security-test is absent from the runtime classpath.


Final review verification completed at **2026-10-19 19:06:20 UTC**: **198 tests,
zero failures/errors/skips**. This covered both notification listeners, multipart
transfer/completion/cancellation/delegation, historical Garage recovery, embedded
HTTP transaction behavior and the production-SDK one-attempt/no-cross-region guard
retained from the retired single-PUT suite. The SDK guard was relocated after the
clean full run; the final focused run covers that added case and the removal of
now-duplicate DELETE-overload assertions.

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=S3MultipartStorageTest,MultipartCancellationIntegrationTest,MultipartCompletionIntegrationTest,MultipartTransferIntegrationTest,DelegatedMultipartIntegrationTest,UploadSuccessGarageIntegrationTest,HierarchyHttpTransactionTest,PurgeNotificationListenerTest,PermissionEventListenerTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=backend_consolidation_review' '-Dspring.flyway.schemas=backend_consolidation_review' '-Dspring.flyway.default-schema=backend_consolidation_review' test
```
