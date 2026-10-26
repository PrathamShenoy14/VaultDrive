# VaultDrive architecture

Last reconciled with the repository on 2026-10-26. This is the current implementation map. Earlier phase records and ADR extensions preserve history; their original boundaries are not the current inventory.

## Frontend M1–M6: authenticated workspace and account experience

`frontend/` is an independently buildable Next.js App Router/strict TypeScript app
with Tailwind, shadcn Base UI, Lucide and next-themes. Server Components guard
`/drive`, `/shared`, `/search`, `/activity`, `/notifications`, `/settings` and
`/trash`; client forms implement real
registration/login/logout, and `/session/restore` uses the sole Route Handler
refresh coordinator. Profile and logical quota come from the backend. M2 adds
root/child file and folder feeds, capability-aware Shared entry points and traversal,
filename/filter search, fresh metadata details, and read-only common-entry Trash.
M3 adds picker/drop uploads, a persistent
workspace transfer panel, bounded direct multipart part uploads and explicit
completion/cancellation/empty recovery. File menus and selection offer streaming
authenticated downloads. M4 adds folder creation, rename/move, Trash/restore,
permanent-deletion status and owner-only file/bounded-folder copy through the BFF.
A workspace operation journal retains keys/IDs, uncertainty and explicit continuation/
recovery controls; see [M4 management](frontend-m4-management.md). M5 adds owner-only
registered-email direct grants, read-only inherited grants and effective-role displays,
plus expiring/revocable file Viewer links. The public share page strips fragment
secrets and uses isolated header-based metadata and native streamed download adapters;
see [M5 sharing](frontend-m5-sharing.md). M6 adds a bounded unread-count bell/panel,
paginated recipient notifications, separate owner resource/permission histories,
display-name editing and exact logical quota details; see
[M6 account/activity](frontend-m6-account-activity.md). Logout cancels M6 requests
and broadcasts credential-free session changes across tabs. Preview remains
deferred. [M3's state/transport/verification contract](frontend-m3-transfers.md)
describes two file tasks, one sliced 8 MiB part per file, at most 50 panel entries,
metadata-only tab recovery and the unresolved browser HTTPS/CORS/clock gates.

Explorer URL parameters preserve folder/search/filter/view state across reload and
history navigation. Each file/folder/Trash feed requests 50 entries independently
and loads further pages only on user action. Server fixed orders are displayed;
no arbitrary sort parameter or misleading sorting of a loaded subset is offered.
Breadcrumbs read only returned accessible parent IDs, stop at null/denial, and
use Shared entry points without asserting a collaborator's root. Traversal is
cycle/depth bounded. Roles/capabilities are backend snapshots, never client grants.

The M2 BFF adds only documented metadata/list/search GETs and per-route query
allowlists, rejects duplicates/oversized queries, and preserves lossless int64
strings/counters. Client feeds abort on scope changes; stale completions cannot
replace a new scope. Navigation changes scope; refresh rereads already loaded pages and reconciles
selection without resetting URL/view/history state. List/grid switching preserves both. Explicit refresh rereads resources and server quota.
Simple local feed state and native selection controls need no Query/Table dependency;
there is no cross-session client resource cache or automatic request retry.

The same-origin allowlisted BFF holds JWT/CSRF/current refresh state server-side,
uses a Secure/HttpOnly/Strict opaque host cookie and forwards individual backend
Set-Cookie/error/Retry-After headers. Per-session serialization prevents rotation
races across tabs **within one supported long-lived Node process**. No RSC or
middleware refresh path exists. Expiry never extends the backend seven-day family;
uncertain refresh requires login. Restart loses local sessions and fails closed.
Horizontal/serverless deployment needs a shared durable session coordinator first.
See [ADR-0017](decisions/0017-frontend-bff-sessions.md) and the
[frontend guide](../frontend/README.md) for routes, security, generation, commands
and verification limits. OpenAPI-derived int64 types and lossless JSON parsing
preserve quota precision. Authenticated pages/responses/fetches are not cached.

No backend API, migration, storage protocol or shared infrastructure is changed.
Local HTTP integration and Chrome layout checks do not establish trusted-HTTPS
browser authentication or close the existing browser multipart/CORS/clock gate.

## MVP API freeze and frontend handoff

The [freeze record](api-contract-freeze.md) covers 73 HTTP operations on 63 paths
and 61 record schemas. [OpenAPI 3.1 JSON](openapi.json) and the [inventory](api-endpoint-inventory.md)
are generated in tests from the running MVC registry/DTOs plus reviewed
security/status/state metadata. Normal tests detect artifact drift; representative
HTTP bodies are checked against those schemas. No runtime documentation dependency
or public docs endpoint is added. Unsupported response media has a sanitized 406.

The [Next.js guide](frontend-integration.md) specifies same-origin HTTPS proxying,
credential/CSRF rotation, redacted navigation, transfer/copy/deletion states and
explicit recovery. No new endpoint is required for those bounded MVP flows.
Public-link downloads retain octet-stream MIME, including empty files. Contract
readiness does not close the [real-browser gate](browser-multipart-verification-2026-10-23.md)
or operational launch gates. Frontend M1–M5 is implemented as described above;
deployment and infrastructure remain outside that milestone.

## System and responsibility boundaries

VaultDrive is a Java 21 / Spring Boot 4.1.1 backend. PostgreSQL owns searchable metadata, authorization, authentication sessions, lifecycle intent, outbox/jobs and durable evidence. Garage 2.3.0 owns object bytes through `ObjectStorageService`. RabbitMQ carries at-least-once notifications for committed PostgreSQL work. Neither external system shares a metadata transaction.

```text
Client -- bearer/JSON control API --> Spring Boot --> PostgreSQL metadata/evidence
   |                                    |-- ObjectStorageService --> Garage
   `-- signed exact UploadPart PUT ----> Garage
                                        `-- outbox --> RabbitMQ --> ingestion
                                                                    --> PostgreSQL jobs/notifications
```

| Responsibility | Supported implementation |
|---|---|
| Registration, login, refresh/logout, profile | `auth`, `security`, `user`; BCrypt, bearer JWTs, PostgreSQL families/revocation |
| Metadata, Trash, restore, purge intent | `folder`, `file`, `hierarchy`; service transactions and PostgreSQL constraints |
| New uploads | Multipart session/transfer/completion/cancellation services; direct multipart for positive sizes; guarded zero-byte SERVER_PUT creation |
| File/folder copying | Owner-only M1 COPY protocol plus bounded M2 immutable subtree jobs, hidden staging and atomic publication |
| Storage I/O/signing | `ObjectStorageService` / `S3ObjectStorageService`, immutable binding, SDK retries disabled |
| Resource authority/reads | `ResourceAccessRepository`, `ResourceReadService`, PostgreSQL resolver snapshots |
| Filename search/filtering | `search`; combined file/folder pages through the same PostgreSQL access resolver |
| Grants, links, permission activity, notifications | `sharing`, permission ingestion and isolated public-link security |
| General resource history | `activity`; immutable owner-only snapshots joined to existing mutation transactions |
| Publication and ingestion | PostgreSQL outbox, confirmed mandatory RabbitMQ delivery, typed transactional ingestors and shared bounded ACK policy |
| Execution and recovery | PostgreSQL jobs, bounded worker/file/folder handlers, retained evidence and independently opt-in missing-job reconciliation |

The [consolidation audit](backend-consolidation.md) records removals, retained history, verification and deferred decisions. [ADR-0010](decisions/0010-backend-consolidation.md) records the consolidated boundaries.

## Persistence and schema

Flyway V1-V46 is append-only; Hibernate validates the schema. JPA manages users, folders, files, immutable outbox writes and short job/outbox entity transactions. Native EntityManager SQL implements PostgreSQL recursive authorization, scalar evidence reads, row locks, claims and conditional transitions. JdbcTemplate manages authentication sessions/revocations without a parallel JPA entity model. All use the configured datasource/transaction manager.

Services own transactions and `spring.jpa.open-in-view=false` remains required. SQL stays in its existing responsibility's transaction service or access repository. A blanket JPA/JDBC conversion would obscure locking and atomicity. Managed mutation targets are refreshed where required; status/version bulk CAS flushes/clears the context. Scalar worker reads avoid stale managed state; independent job/outbox transactions bound cache lifetime. Never save an older entity after native SQL changed that row.

PostgreSQL is the final invariant guard: case-insensitive email uniqueness, same-owner parent foreign keys, active sibling-name uniqueness with root NULL semantics, file versions, immutable allocation/dispatch identities, monotonic progress and proof-dependent removal. Application checks improve errors while constraints decide competing commits.

## Authentication and authorization

Registration normalizes email and enforces BCrypt's 72-byte input bound. Login/refresh issue 30-minute HS256 JWTs with unique `jti` and family `sid`. Seven-day absolute refresh families retain hashed 256-bit credentials, consumed history and hashed CSRF secrets. Family locking precedes fresh token reads. Reuse commits family revocation before returning an error; logout revokes the family and presented jti. Every bearer request checks PostgreSQL and fails closed.

Secure HttpOnly SameSite=Strict host-only refresh cookies are confined to the same-origin login/refresh/logout contract. Custom login headers and session-bound CSRF protect cookie mutations. Other APIs are bearer-only; profile records expose no credential state. See [authentication sessions](authentication-sessions.md).

Resource reads bind ownership, accessible ancestry, lifecycle and strongest direct/inherited Viewer/Editor role in one statement snapshot. Shared entry points filter authority before count/page and redact private parents. Mutation authorization is fresh under owner hierarchy coordination. Owners manage moves, restore, purge, grants and links. Editors have the specifically supported content operations and multipart admission into editable folders.

Authenticated filename search uses `SearchController` -> transactional `SearchService`
-> `ResourceAccessRepository` -> the existing V32 resolver. A possible-owner set
(actor plus owners with a current direct grant) and literal name predicate reduce
candidates; neither replaces effective authorization. Materialized accessible and
filtered sets bind redacted metadata, count and deterministic mixed-resource pages
to one statement snapshot. SQL filters cover type, exact MIME, inclusive size and
creation/modification bounds, and owned/shared scope. No Java tree traversal,
permission cache, copied descendant grants or storage calls are introduced.
V40 adds public `pg_trgm` and partial active-name GIN indexes. Short queries may
scan; transaction/statement/lock budgets bound work, without a performance claim.
See [the endpoint and validation contract](api-contract.md#filename-search-and-filtering-2026-10-22).

File Viewer links use hashed secrets in a dedicated header, isolated public GET security, uniform failures, no-store/no-referrer/nosniff and bounded per-process request/download admission. Downloads stream through the backend without presigned GETs. Admitted streams may finish after revocation. Immutable activity/outbox and recipient-isolated notifications survive resource changes without conferring authority. See [Sharing M1–M6](sharing-permissions-design.md).

## API contract consistency

[The current API contract](api-contract.md) defines fresh authorization-dependent
capabilities, bounded folder and owner Trash pages, common Trash lifecycle entries,
pending-ancestor exclusion and retained owner-only deletion-operation polling.
202 returns the original event UUID/Location. Polling distinguishes accepted,
processing, blocked, failed and receipt-backed logical completion; it never
asserts physical reclamation. Errors share the uppercase ApiError envelope across
authenticated and public-link endpoints, preserving public-link uniform 404s.
Read projections add no migration, mutation authority or lock-order change.

## Concurrency model

[ADR-0007](decisions/0007-concurrency-locking.md) is the exact lock-order authority. Transaction advisory locks use Hibernate's enlisted connection and stable keys. Collisions conservatively serialize work. Only immutable identity may precede coordination.

- Folder structural/grant mutations use exclusive owner hierarchy coordination. File operations use shared coordination plus optimistic versions or fresh eligible-row locks.
- Restore uses hierarchy → eligible file row → destination namespace lock.
- Multipart uses hierarchy → fresh file → allocation → session → decision/progress.
- Copy uses hierarchy, admission-only idempotency lock, lexical-UUID file locks,
  allocations in the same order, then operation; new admission takes destination
  namespace before inserting its new file/quota reservation. Folder copy disposal
  locks copy progress after allocation and before work/member/job.
- Folder copy admission and job mutations use exclusive owner hierarchy before
  protected reads. M1 file/allocation/operation/namespace locks precede job
  checkpoint writes. Storage execution retains M1's shared order. Cancellation
  closes dispatch/publication; separate discard reuses existing folder purge.
- Purge uses hierarchy → resource rows → allocation/work/run/progress → final job fence. Metadata pages use exclusive coordination and UUID row order. Job-only transactions acquire no later resource locks.
- Authentication locks its refresh family before mutable family/token reads.
- Charge changes lock the owner quota row from the file trigger: after a new reservation file insert and before its new allocation/session, or after existing-file lifecycle coordination. Counter repair uses exclusive hierarchy, then quota row, then a fresh aggregate. No lock spans Garage I/O.

No transaction or hierarchy lock spans Garage, signing or broker I/O. Status/version CAS and live token/clock fences reject stale transitions. Pending-purge ancestry blocks restore root fallback and subtree escape. Lock-scope performance remains unmeasured.

Cross-owner filename search follows Shared with me's single-statement snapshot
model, acquiring no multi-owner hierarchy locks or resource row locks. Mutations
retain all existing coordination and lock order. An overlapping transaction may
commit after the search snapshot; result capabilities are informational and every
later operation must freshly authorize. Search does not promise linearizable
revocation or a snapshot across offset-page requests.

## General file and folder activity

V41 adds immutable `resource_activity_events` and frozen completion-actor columns.
`ResourceActivityService` appends database-only historical snapshots in the existing
successful mutation transactions: create, rename, move, Trash, restore, READY and
proof-backed permanent metadata removal. Editors remain distinct from owners.
Multipart records the decision actor at freeze and reuses it during recovery;
pre-V41 decisions retain an explicitly documented initiator fallback. No-ops,
rollback, stale optimistic writes and retained READY/finalization replays emit
nothing. PostgreSQL guards shapes, immutability and unique terminal events.

Authenticated `/api/v1/activity` uses immutable owner-only visibility and bounded
filters/pages in a single statement. Collaborator/actor status never grants
history or live authority, avoiding historical-name/private-parent leaks after
move or revoke. No resource/account FK cascades remove snapshots; retention
matches existing permission history. Permission events/outbox/notifications stay
separate. Existing hierarchy/row/allocation/session/work/job ordering, quota
accounting and external-I/O boundaries are unchanged. See the
[complete event/API/retention contract](resource-activity.md).

## Direct multipart and retained historical uploads

The zero-byte extension uses `EmptyFileController`, transaction-free
`EmptyFileService` and short `EmptyFileTransactions` boundaries. V46 retains one
owner/initiator/key intent and immutable actor snapshot, with a new independent
file/allocation/key per creation. Its zero-only SERVER_PUT dispatch is granted
once, exact-key HEAD positively verifies creation marker/length/type/ETag, and a
genuine V25 receipt commits before existing fresh READY CAS and UPLOAD_COMPLETED
history. Recovery never repeats PUT. Locked quota admission checks even zero
length; used/reserved remain unchanged. Real objects keep downloads, file/folder
CopyObject and exact-key purge compatible. Pending ancestry blocks publication;
admitted unknown writes retain the conservative SERVER_PUT purge barrier. See
[ADR-0016](decisions/0016-zero-byte-files.md) and
[the endpoint/recovery contract](api-contract.md#zero-byte-file-creation-2026-10-23).

POST `/api/v1/upload-sessions` is the nonempty-file admission. It atomically reserves UPLOADING metadata, a DIRECT_MULTIPART allocation and an idempotent session with immutable owner/initiator, destination, size/type, geometry and expiry. Retired POST `/api/v1/files` returns 405 to authenticated callers; anonymous callers still require authentication. No synchronous PUT fallback exists.

Only a newly committed initiation permission permits CreateMultipartUpload. OPEN requires the retained exact upload ID. Per-part length/SHA-256 is immutable; only UploadPart is presigned, for bounded lifetimes/batches. Resume uses bounded authoritative ListParts pages. Fresh actor/ancestry authorization gates admissions and responses. Revocation cannot recall existing URLs.

The [2026-10-23 browser recheck](browser-multipart-verification-2026-10-23.md)
confirmed HTTP-only Garage and denied origin preflight, with no connected browser.
The harness now compares browser part receipts exactly with backend ListParts and
provides explicit identical retry and before/after native-expiry controls. Node
and backend results remain separate evidence; live browser/TLS/CORS release and
monitored clock bounds are still open. Downloads remain authenticated backend
streams; no presigned GET or browser-to-Garage control-plane CORS is required.

Completion freezes one authoritative manifest and dispatch identity, commits strict positive object evidence in a separate immutable receipt, then performs fresh READY CAS. Recovery uses that receipt or strict checksum/metadata evidence for the original dispatcher. Unknown Create/Complete/Abort never grants redispatch. Cancel/expiry records mutually exclusive abandonment and releases eligible names via FAILED; abort acknowledgement never proves physical reclamation.

Historical SERVER_PUT allocations, receipts, migrations and targets remain. Read-only inventory, verified never-admitted expiry, receipt-only READY recovery, restricted receipt-backed FAILED cleanup and historical SUCCEEDED purge recovery remain explicit internal operations. Missing receipts/unknown bindings are not backfilled from current settings or negative observations. ADR-0016 adds narrowly scoped zero-byte SERVER_PUT admission, one PUT dispatch and positively verified V25 receipts; general synchronous stream upload remains retired.

The size policy remains positive files up to five GiB, fixed eight-MiB parts and bounded part count. Zero-byte creation uses the separate [ADR-0016 path](decisions/0016-zero-byte-files.md); multipart still rejects zero. See [sessions](multipart-upload-sessions.md), [transfers](multipart-part-transfers.md), [completion](multipart-completion-recovery.md), [cancellation](multipart-cancellation-expiry.md) and [historical recovery](upload-success-recovery.md).

## Trash, purge and asynchronous work

Copy M1 adds `FileCopyController`, `FileCopyService` and short
`FileCopyTransactions` boundaries. V42 retains independent COPY allocations,
owner/key-unique immutable intent, single-assignment dispatch and positive object
receipt. Admission atomically reserves quota and name; source/destination
ownership and lifecycle are checked under hierarchy/row coordination at admission
and dispatch. One bounded CopyObject outside transactions uses source ETag and
backend operation metadata. Positive exact-key verification commits a receipt
before READY, quota transfer and COPIED history commit together. Recovery never
repeats an uncertain copy. Logical cancellation releases charge through FAILED
without claiming bytes gone. Source deletion after dispatch cannot remove a
verified independent copy. Existing READY purge uses COPY-specific receipt
verification; unfinished sealed folder members use separate copy_discarded_at
logical evidence and retain possible late effects. Retained owner-only polling
survives resource removal and provides no current access capability. See
[protocol, API and recovery](file-copy.md) and [ADR-0012](decisions/0012-safe-file-copy.md).

Copy M2 adds caller-driven `FolderCopyController`, `FolderCopyService` and short
`FolderCopyTransactions` boundaries. V43 freezes at most 1,000 active folder/READY
file members and names under exclusive owner coordination, with independent
folder/file identities and one linked M1 operation per file. At most four member
steps per explicit execute/recover request advance durable checkpoints; recovery
never reserves or redispatches a file. The access resolver and scalar mutation
checks hide staging members until receipt-backed whole-tree publication and
folder COPIED history commit atomically. M1 file history still records each
independently committed READY member. Quota is incremental and hidden READY files
remain used. Job cancellation permanently closes publication without erasing
charges or uncertain facts; explicit idempotent discard submits existing bounded
folder purge. See [the complete API, snapshot and operational contract](folder-copy.md)
and [ADR-0013](decisions/0013-bounded-folder-copy.md). This stops at M2.

Per-owner quota accounting uses READY file lengths as used and UPLOADING lengths
as reserved. Trash and pending purge remain charged through durable file metadata
removal. The authenticated [usage API and repair procedure](storage-quotas.md)
report limit/used/reserved/available plus separately inventoried uncertain
write-admitted allocations. Unknown physical reclamation never increases logical
capacity by itself. [ADR-0011](decisions/0011-storage-quota-accounting.md) records
the lock and backfill rules.

V44 adds immutable logical quota crossing snapshots and atomic outbox pointers
from that same locked accounting row. Every newly reached 80/90/100 boundary
alerts only its owner, including Editor-caused reservations. Exact positive
used-plus-reserved comparisons, strict-below rearming and limit-change handling
derive state directly from counters/limit; READY and idempotent recovery preserve
the total. Existing usage is the rollout baseline. The existing permission queue,
listener, event/recipient-deduplicated notification table and JWT list/unread/read
APIs carry the alerts; grant display/recipient isolation is preserved. No new
queue, pipeline or external I/O in transactions is introduced. Alerts remain
historical information and never authorize upload/copy. See
[the threshold/API/operator contract](quota-notifications.md) and
[ADR-0014](decisions/0014-storage-quota-notifications.md).

Only accessible active READY files are usable. Rename/move update metadata without object copies. Trash retains bytes and releases names; restore keeps identity and resolves collisions while preserving extensions. Purge requests record intent/outbox atomically, return 202 and perform no storage I/O. Pending folder intent makes its subtree immutable.

The publisher claims committed rows, publishes outside transactions and requires broker confirm without mandatory return before PUBLISHED. Uncertain outcomes replay the same event UUID. Typed consumers validate source identity, commit deduplicated jobs/notifications or sanitized quarantine, then ACK. Both use one three-attempt delivery policy; exhaustion stops unacknowledged work. Permission routing remains separate from purge.

Workers reserve bounded capacity before claims, renew live leases, check cancellation and record fenced outcomes. Useful CONTINUE checkpoints do not spend failure budget; failures/abandoned claims do. Byte deletion and metadata receipts are separate. Folder discovery seals membership before file work, then bounded bottom-up metadata pages retain child/root receipts and FINALIZED proof. Historical SUCCEEDED means byte success; missing metadata alone is not proof.

READY multipart deletion requires original completion evidence/binding. Unfinished folder members use logical-discard receipts and retain possible storage effects. Folder FINALIZED does not assert reclamation of discarded effects. Garage deletion relies on trusted writers and permanent key nonreuse; leases cannot fence external effects.

Publisher, purge consumer, permission consumer, worker and missing-job reconciler are independently disabled by default. Reconciliation inserts only aged-PUBLISHED, unquarantined matching intent/target/run work; it never resets jobs/proofs. See [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md), [file purge](file-purge.md), [folder purge](folder-purge-design.md), [M5](multipart-purge-compatibility.md) and [missing-job recovery](missing-job-reconciliation.md).

## MVP operational hardening

Four constant-memory process-wide token buckets/semaphores bound authentication,
multipart control/signing, authenticated download lifetimes and metadata/search/
copy work before bearer database checks. Rejection returns common 429/no-store/
Retry-After: 1. A fixed async executor and cancellation-aware stream lease retain
capacity until actual streaming exits and close callbacks that never start.
Partial SDK streams abort instead of draining. Public links keep their separate
synchronous admission. Fleet fairness/rate limiting requires the deployment edge.

Independently opt-in logical multipart expiry and historical SUCCEEDED standalone
purge finalization process one bounded keyset page per fixed-delay tick. They use
existing durable protocols and lock order and perform no storage I/O. Restarts and
duplicate sweeps retain decisions/receipts; expiry grants no Abort or reclamation.
All seven processors and probes remain off locally. The deployment profile requires
their explicit enablement, bounded servlet/database resources and validated settings
before any runtime bean starts. Original publication/ingestion/worker fences remain.

Public liveness stays process-only. Cached public readiness reports UP/DOWN without
dependency details; sanitized local logs expose PostgreSQL/Garage/Rabbit health,
both required consumers, worker/control-loop freshness, admission totals and capped
stalled/exhausted/quarantined/pending-recovery observations. A stale probe fails
closed. Logical-expiry storage uncertainty remains visible without physical proof.

The nonweb operator profile disables migrations and every processor. Exact retained
FAILED/PUBLISHED source replay uses normal strict ingestion and keeps the original
event UUID, publication/quarantine history and notification read state. V45 retains
incident audit and permits only an audited 1..10-failure extension of an exact
exhausted job (total cap 1,000), preserving counters/checkpoints/receipts. It changes
no resource-lock order or uncertain storage dispatch authority. See
[ADR-0015](decisions/0015-mvp-operational-hardening.md),
[deployment/recovery/monitoring and launch gates](operational-hardening.md).

## Test evidence

Tests cover unit/controller contracts, real PostgreSQL constraints/rollback/races, embedded HTTP/OSIV, isolated RabbitMQ and disposable Garage behavior. Historical fixtures explicitly reconstruct old state without a production compatibility adapter. Removed-route tests check rejection without metadata/allocation/storage effects and direct-session reservation/idempotency. Process harnesses now use direct multipart HTTP setup with their independent execution gates.

[Consolidation verification](backend-consolidation.md#verification) records final results and gated skips. Historical [continuous purge](async-purge-verification.md) and [process recovery](async-process-recovery.md) records are evidence for their recorded revisions, not claims that gated runs executed now.

The MVP quota milestone's final 2026-10-21 clean backend run passed 1,331 tests
with zero failures/errors and 20 gated skips on isolated PostgreSQL schemas,
including live RabbitMQ, Garage multipart and the HTTP-to-Garage purge harness.
The skipped checks were process-kill recovery (1), the separate Garage capability
characterization (18), and retained-journal recovery (1). Focused quota,
delegated upload, lifecycle and purge races also passed. Test isolation was
extended to older fixed-schema suites without changing their shared data.

The [browser harness](browser-multipart-interoperability.md) provides exact-body/checksum, refresh serialization and proxy checks. **Live browser interoperability remains unverified** pending approved HTTPS, real-origin CORS, browser execution and bounded clocks. Java/Node checks do not establish browser behavior.

### Filename search verification

On 2026-10-22, the final focused selection (`SearchIntegrationTest`,
`ApiContractConcurrencyIntegrationTest`, `SharingIntegrationTest`,
`SharingConcurrencyIntegrationTest`) passed **134 tests, zero failures/errors/skips**.
The 39 search cases use a fresh `search_<UUID>` PostgreSQL schema and committed
per-actor fixtures. They cover owner/Viewer/Editor/stranger access, multiple owners,
direct/inherited/overlapping grants, private parents/siblings, literal `%`, `_`,
`!` and backslash, Unicode names, MIME declarations with charset, zero/nonzero size,
inclusive dates, invalid bounds, deterministic type/UUID tie breakers, empty/large
offset pages, non-READY/Trash/purge barriers, cycles/depth and sanitized lock timeout.
Latch-held transactions verify old committed search content/count/role/parent state
during revoke, downgrade, file/folder move, Trash, restore and purge, followed by
fresh post-commit visibility and ordinary operation reauthorization.

The final clean full suite passed **1,370 tests: 1,350 passed, 20 skipped, zero
failures/errors**, against PostgreSQL 17.11 and inspected Garage 2.3.0/RabbitMQ
4.3.6. It includes 12 live isolated RabbitMQ tests, 27 gated Garage multipart cases,
and the real HTTP-to-Garage asynchronous purge harness. Both harness events reported
`initialDueAligned=false`; the test-only scheduling adjustment was unnecessary.
The task-owned tunnel was closed after verification. Synthetic verification
schemas remain retained; the continuous harness cleaned only its own allocations
and private topology/schema. No shared migration checksum was repaired.

| Gated suite | Skipped | Separate prerequisite, not enabled |
|---|---:|---|
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST` and M0 characterization |
| `GarageMultipartJournalRecoveryTest` | 1 | Separately reviewed retained-run UUID |

These skipped scenarios are unverified by this milestone. `git diff --check`,
staged-file/diff review and local documentation link checks also passed before
commit. The real table-lock test exposed JPA `PessimisticLockException` on native
search reads; the common timeout advice now includes JPA pessimistic/lock timeout
exceptions so that path returns sanitized 503 instead of 500.

Reproduce from `backend/` with the ignored environment loaded in the Maven shell,
the task-owned AMQP tunnel and existing private-vhost isolation from
[the continuous runbook](async-purge-verification.md). Set
`RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`, `ASYNC_E2E_TEST=true`,
`ASYNC_E2E_ALIGN_INITIAL_DUE=true`, `GARAGE_MULTIPART_TRANSFER_TEST=true`,
`GARAGE_MULTIPART_COMPLETION_TEST=true`, `GARAGE_MULTIPART_CANCELLATION_TEST=true`,
`GARAGE_MULTIPART_PURGE_TEST=true`, and `GARAGE_MULTIPART_VERSION=2.3.0`, then run:

```powershell
.\mvnw.cmd -o clean test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=filename_search_final_verification' '-Dspring.flyway.schemas=filename_search_final_verification' '-Dspring.flyway.default-schema=filename_search_final_verification'
```

Default contexts use that isolated test schema; suites with their own fresh schema
retain their isolation. Normal publisher/consumer/worker/reconciler defaults stay
off. No development metadata, shared fixture repair, VM configuration or applied
migration is changed. Search adds no external I/O, mutation or lock-order change.
V40 installs `pg_trgm` only in the tested database; production deployment still
needs the documented extension prerequisite. Capacity, latency, browser behavior,
cross-request snapshots and content/full-text/semantic search remain unverified
or outside scope.

### General activity verification

On 2026-10-22, the initial focused selection passed **279 tests** and the expanded
PostgreSQL/API/race/Garage/RabbitMQ selection passed **324 tests**, both with zero
failures/errors/skips. The expanded selection includes 27 live Garage multipart
cases, 12 isolated RabbitMQ cases and the continuous HTTP-to-Garage purge harness.
Final attribution provenance, additional identical-folder-rename concurrency,
upgrade/no-backfill checks and HTTP retained-history assertions are included in
the subsequent clean full run below.

The final clean full suite passed **1,402 tests: 1,382 passed, 20 skipped, zero
failures/errors**. All 29 new activity API/PostgreSQL cases passed, including
immutable actor attribution, failed inserts/rollback, concurrent no-op rename,
revocation/privacy, snapshots, bounded filters/pagination and lock timeout. Existing
sharing races assert the authorized/optimistic winner's event. READY and purge
suites assert atomic history/proof, duplicate recovery, stale-fence rollback,
retained recursive history and standalone/folder finalization participation.
Live Garage cases assert one completion event. The HTTP purge harness reads
owner history through the authenticated API after metadata removal; both events
reported `initialDueAligned=false`, so no scheduling adjustment was needed.

| Gated suite | Skipped | Separate prerequisite, not enabled |
|---|---:|---|
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST` and M0 characterization |
| `GarageMultipartJournalRecoveryTest` | 1 | Separately reviewed retained-run UUID |

These scenarios remain unverified by this milestone. `git diff --check`, staged
file/diff review and 123 local documentation file-link checks passed. The task-owned
tunnel was closed after verification. Synthetic immutable history remains only in
isolated test schemas; no shared metadata, migration checksum, VM configuration,
service credential, storage identity or quota policy was changed.

Verification uses PostgreSQL 17.11, read-only inspected Garage 2.3.0 and RabbitMQ
4.3.6. Default test contexts use only `vaultdrive_test.activity_final_verification`;
suites with their own fresh UUID schemas retain isolation. The new history suite
uses `activity_<UUID>`, committed per-actor fixtures and no shared-schema repair.
Normal async execution defaults remain disabled. Live broker tests use the existing
private `vaultdrive_outbox_test` vhost via the task-owned localhost SSH tunnel.
Garage fixtures use only their disposable resources and established uncertainty
journals; no retained ambiguous allocation or reviewed journal is adopted.

Reproduce from `backend/` with the ignored local environment loaded in the Maven
shell and the documented [verification tunnel](async-purge-verification.md):

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST = 'true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE = 'true'
$env:GARAGE_MULTIPART_TRANSFER_TEST = 'true'
$env:GARAGE_MULTIPART_COMPLETION_TEST = 'true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST = 'true'
$env:GARAGE_MULTIPART_PURGE_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o clean test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=activity_final_verification' '-Dspring.flyway.schemas=activity_final_verification' '-Dspring.flyway.default-schema=activity_final_verification'
```

Use a fresh verification schema when reproducing a changed migration revision;
never repair checksums in shared data. The continuous harness independently owns
its schema, topology and disposable allocation cleanup. Private credentials and
local environment files are never committed. Performance, cross-request snapshots,
physical reclamation, live browser release and retention/erasure remain outside
this milestone; see [the full history contract](resource-activity.md).

## Copy M1 verification

Executed on 2026-10-22 with PostgreSQL 17.11, read-only inspected Garage 2.3.0
and RabbitMQ 4.3.6. Final focused copy/adapter/Garage/activity/multipart-purge
selection: **99 passed, zero failures/errors/skips**. Final clean full backend
suite: **1,446 tests, 1,426 passed, 20 skipped, zero failures/errors**.

The 44 new cases comprise 38 isolated PostgreSQL/API/race cases, three adapter
cases and three live Garage cases. They cover owner versus Viewer/Editor/stranger
authorization, lifecycle/ancestry, collisions, concurrent idempotency/reservation/
dispatch, quota exhaustion/rollback, unknown/mismatched bindings, receipt/READY
rollback, READY versus cancel, source Trash versus dispatch and destination Trash
versus READY in both lock orders, stale folder job fencing, independent deletion,
logical disposal and retained late positive facts. Blocking tests observe actual
PostgreSQL waiters and re-read committed state after locks.

Live Garage tests copy a real multipart source, verify exact downloaded bytes and
replacement operation metadata, independently purge source/destination, and retain
a receipt when the destination is trashed during copy. A one-request TCP proxy
consumes Garage's successful CopyObject response and drops it downstream; the
operation remains UNKNOWN and positive HEAD recovers READY with no second copy.
Three run journals report no retained ambiguous copy allocations. Their cleanup
touches only freshly owned, positively verified destinations and known complete
source/control objects. Physical block duplication/reclamation is not inferred.

The full suite includes 12 live isolated RabbitMQ tests, 27 enabled Garage
multipart cases and the continuous HTTP/outbox/broker/job/Garage purge harness.
Default contexts use only `vaultdrive_test.copy_final_f27548d6218f48a58faf5beb0b2e1312`;
suites with explicit fresh schemas retain their own isolation. No shared metadata,
allocation, migration checksum, VM/storage/broker configuration or normal async
execution default changed. The task-owned localhost AMQP tunnel was closed after
verification. Synthetic database evidence and local test journals remain retained.

| Separately gated suite | Skipped | Gate not enabled |
|---|---:|---|
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and process-kill runbook |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and separate M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | Explicitly reviewed retained-run UUID |

These scenarios remain unverified by Copy M1. Five-GiB capacity, multi-node
failure, latency, browser/client integration, physical reclamation and retention
policy are unverified or undecided. A dispatch-commit/crash gap can remain UNKNOWN
forever; no negative probe permits redispatch. Logical cancellation/disposal
retains possible bytes and is not automatic physical cleanup.

Reproduce with a task-owned [AMQP verification tunnel](async-purge-verification.md),
the ignored environment loaded in the same Maven shell and a fresh schema:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
$copySchema='copy_verify_'+[Guid]::NewGuid().ToString('N')
.\mvnw.cmd -o clean test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$copySchema" "-Dspring.flyway.schemas=$copySchema" "-Dspring.flyway.default-schema=$copySchema"
```

See [the copy API and operator recovery procedure](file-copy.md) and
[ADR-0012](decisions/0012-safe-file-copy.md). Copy M1 stops before folder copy,
frontend and quota notifications.

## Copy M2 verification

Executed on 2026-10-22 against PostgreSQL 17.11, read-only inspected Garage 2.3.0
and RabbitMQ 4.3.6. Final focused selection (`FolderCopyIntegrationTest`,
`FileCopyIntegrationTest`, `GarageFileCopyIntegrationTest`, `S3CopyStorageTest`,
`FolderAccessValidatorTest`): **88 passed, zero failures/errors/skips**.

Final clean full backend suite: **1,483 tests, 1,463 passed, 20 skipped, zero
failures/errors**, including 12 isolated live RabbitMQ cases, 27 enabled Garage
multipart cases, all six live copy cases and the continuous HTTP/outbox/broker/
job/Garage purge harness. The harness required no initial due-time alignment and
removed only its seven owned objects and fresh schema/topology. Default contexts
used only `vaultdrive_test.folder_copy_final_c21d8aeeaf16432395e784e5e7ae8ae3`;
explicitly isolated suites retained their own fresh schemas. Six copy run journals
reported no retained ambiguous copy objects. The task-owned AMQP tunnel was closed
after verification. No shared metadata, old allocation, migration checksum,
VM/storage/broker configuration or normal async default was changed.

| Separately gated suite | Skipped | Gate not enabled |
|---|---:|---|
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and separate process-kill runbook |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and separate M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | Explicitly reviewed retained-run UUID |


The 34 new isolated PostgreSQL/API cases cover nested/large/maximum 1,000-member
admission and publication, depth/cap rejection, owner versus collaborator/stranger
authorization, naming/collisions, descendant rejection, immutable membership/names,
source changes before/after reservation, public/internal key isolation, concurrent
admission/execution, reconstructed orchestration after interruption, positive-only
UNKNOWN recovery, partial READY invisibility/search/move barriers, quota blocking,
publication/history rollback, durable cancellation, late receipts, independent
purge and parent-led sealed disposal. Latch-held transactions observe actual
pg_blocking_pids and assert both snapshot/Trash and cancel/publication winner orders.

The six live Garage cases include three M2 additions: nested folder publication
with a real multipart object and independent source/destination deletion, successful
CopyObject response loss through the one-request proxy followed by positive HEAD
without redispatch, and destination Trash with receipt-only publication after
restore. Exact downloaded bytes and per-file replacement metadata are checked.
Cleanup touches only this run's fresh positively verified destination keys and
known completed source/control objects; uncertain allocations/journals are retained.

A maximum-size test initially exposed repeated whole-snapshot validation in each
deferred member trigger. V43 now validates mappings per insert, checks completeness
once at admission commit, and verifies each internal M1 linkage once. The final
maximum-size test publishes the complete tree within the configured transaction
bound. No latency, throughput or production-capacity improvement is claimed.

From `backend/`, reproduce after read-only Garage attestation using the task-owned
[isolated AMQP tunnel](async-purge-verification.md) and private environment:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
$m2Schema='folder_copy_verify_'+[Guid]::NewGuid().ToString('N')
.\mvnw.cmd -o clean test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$m2Schema" "-Dspring.flyway.schemas=$m2Schema" "-Dspring.flyway.default-schema=$m2Schema"
```

Separately gated process-kill, capability characterization and retained-journal
recovery are not established by these focused checks. Arbitrary-size snapshots,
five-GiB capacity, multi-node failure, browser/client integration, performance,
physical block independence/reclamation and retention/erasure remain unverified
or deferred. The dispatch-commit/crash gap can remain UNKNOWN forever; no negative
probe permits redispatch. There is no automatic copy executor or uncertain-byte
cleanup. See [the complete operational contract](folder-copy.md).

## Quota notification verification

Executed on 2026-10-22 against PostgreSQL 17.11 and live RabbitMQ 4.3.6;
read-only VM inspection confirmed Garage image 2.3.0. Final focused selection:
**149 passed, zero failures/errors/skips** across `QuotaEventTest`,
`QuotaNotificationIntegrationTest`, `StorageUsageIntegrationTest`,
`SharingNotificationIntegrationTest`, `RabbitSharingNotificationIntegrationTest`,
`FolderCopyIntegrationTest`, `FileCopyIntegrationTest`, `PermissionEventTest`,
`PermissionEventListenerTest` and `PermissionConsumerConfigurationTest`.

The new coverage verifies exact/jumped/rounded/overflow-safe thresholds,
strict-below rearming, zero limits, limit changes, cancellation/expiry/removal,
completion/repair, Editor ownership, file/folder copy retries and recovery,
quota/source/outbox rollback, uncommitted-source isolation, immutable history,
event/recipient guards and authenticated numeric metadata/filter/unread/read APIs.
Latch-held transactions observe actual PostgreSQL blocking for both reservation
orders and both limit-edit/cancellation versus reservation orders. Live broker
checks use the real publisher/shared route plus reversed/duplicate delivery and
commit-before-ACK redelivery without resetting read state. Existing sharing
checks continue to assert recipient isolation and current authorization redaction.

Clean full backend suite: **1,515 tests, 1,495 passed, 20 skipped, zero
failures/errors**. This includes 14 isolated live RabbitMQ checks, 27 enabled
Garage multipart cases, six live copy cases and the continuous HTTP/outbox/
broker/job/Garage purge harness. The harness required no initial due-time alignment
and deleted only its seven owned objects and fresh schema/topology. Default
contexts used `vaultdrive_test.quota_notify_full_8d187f2753824dc58b110380af785e1c`;
explicitly isolated suites used their own fresh schemas. The final focused
follow-up (`FolderCopyIntegrationTest`, `RabbitSharingNotificationIntegrationTest`)
passed **41 tests, zero failures/errors/skips**, verifying broker fixture cleanup
and folder-copy cancellation/discard rearming. The task-owned AMQP tunnel was
closed afterward. Local documentation links and whitespace checks passed.

| Separately gated suite | Skipped | Prerequisite deliberately not enabled |
|---|---:|---|
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and process-kill runbook |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | Explicitly reviewed retained-run UUID |

From `backend/`, after read-only VM attestation and the existing isolated AMQP
tunnel, reproduce with the private environment:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
$quotaSchema='quota_notify_full_'+[Guid]::NewGuid().ToString('N')
.\mvnw.cmd -o clean test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$quotaSchema" "-Dspring.flyway.schemas=$quotaSchema" "-Dspring.flyway.default-schema=$quotaSchema"
```

The three separately gated scenarios remain unverified. Performance, multi-node
failure, browser/client integration, physical reclamation, retention/erasure and
automatic notification reconciliation remain deferred. Processing defaults stay
disabled; no shared metadata, old migration, VM or broker/storage configuration
was changed. Quota notifications stop at this milestone.

### MVP operational hardening verification

Executed on 2026-10-23 against PostgreSQL 17.11, read-only attested Garage 2.3.0
and RabbitMQ 4.3.6. The VM was initially unavailable; verification resumed after
the user started it. No VM, bucket, broker policy, shared metadata, migration
checksum or credential was changed. An initial default-schema check encountered
the already documented V26 checksum drift; subsequent runs used fresh schemas,
without repairing shared history.

Expanded lifecycle/worker/recovery/Garage/RabbitMQ selection: **229 passed**.
Correction selection: **63 passed**. Final policy/operator/audit/readiness selection,
including live dependencies: **23 passed**. All had zero failures/errors/skips.
The first full run caught an overly restrictive reconciler filter: unknown source
types must remain visible as blocked. The final query excludes only the known
permission/quota notification routes, with a new parameterized regression.

Final full backend suite: **1,554 tests, 1,534 passed, 20 skipped, zero failures
or errors**, completed at 11:21:28 UTC. Default contexts used only
`vaultdrive_test.hardening_release_41b1239513aa4649a0172f0e33138bb7`; suites with
their own UUID schemas retained that isolation. This includes all **36** new
operational cases, **15** isolated RabbitMQ/readiness cases, **27** enabled Garage
multipart cases, all **six** live copy cases and continuous HTTP/outbox/broker/job/
Garage purge verification. Both continuous intents reported initialDueAligned=false.

Coverage includes concurrent admission, monotonic token refill, synchronous errors,
queued/running cancellation, completed streams and unchecked close failure;
expiry/finalization restart and existing expiry/decision races; required-processor
startup guards before runtime initialization; dependency/consumer/worker/stale
readiness; retained business recovery alerts that keep client recovery reachable;
stalled recipient ingestion observations; strict duplicate replay/read-state
preservation; audited budget rollback, monotonic counters/checkpoints, competing
operators and old-token rejection. A live readiness case stops only its own
consumer and probes an unreachable local broker port; no shared broker is stopped.

Packaging initially lacked cached Maven jar-plugin POM/dependencies. Resolving
those standard Maven Central artifacts required no repository dependency change;
`mvnw.cmd -DskipTests package` then passed. The actual executable jar's nonweb
operator inspection passed against a fresh empty verification schema with all
ordinary processing/probe environment switches set true. The operator profile
kept them disabled, returned zero bounded work counts and exited successfully.

| Separately gated suite | Skipped | Prerequisite deliberately not enabled |
|---|---:|---|
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and separate process-loss/kill runbook |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | GARAGE_MULTIPART_REVIEWED_RUN and reviewed retained UUID |

No forced process kill, multi-node failure, live browser/TLS/CORS release,
physical reclamation, backup restoration or production-capacity result is claimed.
New operational PostgreSQL fixtures stay in private schemas; existing live suites
dispose only their owned objects/topology and retain uncertainty journals under
their established protocols. The task-owned loopback AMQP tunnel was closed after
verification. All 252 checked local documentation file links and the whitespace/
staged-diff checks passed. No production deployment was performed.

From `backend/`, with the private environment and documented isolated AMQP tunnel:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST='true'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE='true'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
$hardeningSchema='hardening_verify_'+[Guid]::NewGuid().ToString('N')
.\mvnw.cmd -o test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$hardeningSchema" "-Dspring.flyway.schemas=$hardeningSchema" "-Dspring.flyway.default-schema=$hardeningSchema"
.\mvnw.cmd -DskipTests package
```

See [the operational limits, recovery commands and launch gates](operational-hardening.md).

## Zero-byte file verification

Executed on 2026-10-23 against isolated PostgreSQL 17.11 schemas and read-only
attested Garage 2.3.0. The final focused selection passed **34 tests with no
failures, errors or skips**: 23 empty-file PostgreSQL/API tests, eight live Garage
copy/empty-object tests and three storage adapter copy regressions.

The final full backend suite passed **1,579 tests: 1,559 passed, 20 skipped, zero
failures or errors**, finishing at 13:24:17 UTC. Default contexts used only
`vaultdrive_test.empty_release_9d2c0faf09d14e26a9e8c12bdddad762`; suites with their
own isolation retained separate fresh schemas. Live Garage multipart/copy,
isolated RabbitMQ and HTTP-to-Garage async end-to-end gates were enabled. The
existing explicit initial-due alignment opt-in handled VM/JVM clock skew only
inside that isolated end-to-end harness. No VM configuration, shared schema,
existing shared storage allocation, migration checksum or credential was modified.

The 20 skips were AsyncPurgeProcessRecoveryTest (one; requires the separate
process-kill runbook), GarageMultipartCapabilityTest (18; separately gated M0
characterization) and GarageMultipartJournalRecoveryTest (one; requires a reviewed
retained journal UUID). These are not passing checks. No browser/TLS/CORS release,
process-kill, multi-node, physical GC, capacity or performance result is claimed.

Coverage includes owner/Editor attribution, Viewer denial/revocation, names,
idempotency, synchronized concurrent creation and namespace collision, both
hierarchy admission and READY-versus-Trash orders, quota zero/full/over-limit
accounting and uncertain allocation counts. Tests also cover declared/default
MIME and Content-Length: 0 on authenticated/public-link downloads, rename/move,
grants, Trash/restore, independently copied file/folder objects, exact deletion
receipts, frozen actor recovery, activity rollback, mismatched evidence, immutable
dispatch/allocation, verified-never-admitted removal and unknown-writer purge
blockers. PostgreSQL mocks only storage calls; the live selection checks real
zero-byte PUT/HEAD/GET/CopyObject/DELETE and injects acknowledgement loss after a
real successful PUT, then proves one PUT and positive-only recovery. That case
does not claim a real network-drop or process-kill test.

Reproduce with the ignored private environment, documented loopback AMQP tunnel
and existing `vaultdrive_outbox_test` vhost. Enable the same Garage multipart,
copy and isolated async end-to-end flags as the operational verification above;
use a new `empty_release_<UUID>` schema for default test contexts. The new focused
selection is `-Dtest=EmptyFileIntegrationTest,GarageFileCopyIntegrationTest,S3CopyStorageTest`
with GARAGE_COPY_TEST=true and GARAGE_MULTIPART_VERSION=2.3.0. Applied historical
migrations/shared allocations are not repaired or modified.

## Remaining work

Unknown multipart reclamation, broad historical repair, retention/erasure, JWT key evolution, restore-tested backups/disaster recovery, clients and actual deployment remain separate. Per-instance admission, dependency/processing readiness and bounded operator recovery are implemented; fleet admission/edge abuse protection, live browser release and production capacity remain launch gates. Performance, multi-node failure and capacity targets are unmeasured. No shared checksum repair, old allocation sweep or VM change is part of consolidation. See the [roadmap](development-roadmap.md) and [risk register](backend-consolidation.md#deferred-decisions-and-risks).
