# Development roadmap

This roadmap separates repository evidence from future intent. Ordering can change after measurement or design review; unchecked items are not implemented.

## Full backend code and architecture consolidation (completed)

- [x] Audit all backend responsibilities, persistence boundaries, dependencies, configuration, migrations and tests.
- [x] Retire synchronous POST `/api/v1/files`, SERVER_PUT admission/PUT and foreground receipt creation; direct multipart is the only supported new upload.
- [x] Retain historical receipts, immutable storage identities, recovery/expiry/cleanup and purge evidence without migration or shared-data repair.
- [x] Consolidate notification delivery policy and remove superseded resource queries, obsolete DTOs/exceptions/tests and duplicate dependency declarations.
- [x] Update current architecture, preserve historical milestone records and add removed-route/historical recovery verification.

See [audit decisions, final verification and deferred risks](backend-consolidation.md) and [ADR-0010](decisions/0010-backend-consolidation.md). Stop at this milestone. Browser release, physical multipart reclamation, retention, zero-byte support and new features remain separate work. Earlier phase sections below are historical records; later completed extensions supersede their original boundaries.

## Current baseline

- [x] Spring Boot API with PostgreSQL and Flyway.
- [x] Registration, login, HS256 JWT issuance/validation, and protected endpoints.
- [x] Owner-scoped folder tree with create/list/get/rename/move/soft-delete/trash/restore.
- [x] Direct multipart upload and backend streaming download through Garage, metadata listing, rename, move, trash, and restore.
- [x] Pending-purge schema/domain state and purge-aware trash/restore queries.
- [x] Permanent-delete request flow: shared hierarchy lock, eligible-row lock, durable pending-purge transition, and `202 Accepted` response without Garage deletion.
- [x] Folder permanent-delete request: exclusive hierarchy lock, non-overlapping subtree intent, pending-purge ancestry barriers, and `202 Accepted` without deletion.

## Phase 1 — Durable permanent-delete intent (completed)

Goal: atomically choose exactly one of restore or permanent-delete for an eligible trashed file.

- [x] Add the authenticated permanent-delete request endpoint and return `202 Accepted` after recording intent.
- [x] Acquire the shared hierarchy lock before fetching and locking restore or permanent-delete eligibility.
- [x] Restrict the transition to owned, trashed `READY` files with no existing purge request.
- [x] Keep Garage deletion out of the HTTP request path.
- [x] Return `404 Not Found` for inaccessible, ineligible, and repeated requests rather than treating repetition as an idempotent success.
- [x] Cover the HTTP contract, service delegation, transition, and lock-before-read interaction order with unit and MockMvc tests.

PostgreSQL-backed races cover both restore/permanent-delete winner orders. Restore also coordinates with folder structural changes and serializes collision naming within its chosen destination.

Folder purge-request races cover child-folder restore, file restore root fallback, folder move, upload reservation, and both parent/child request winner orders. Physical subtree deletion remains a worker concern.

## Phase 1A — RabbitMQ development infrastructure

- [x] Add a pinned management image, persistent data volume, stable hostname, restart policy, health check, and required credentials from ignored environment configuration.
- [x] Inspect the existing VM Compose project and verify Windows-to-VM SSH forwarding before selecting loopback-only ports.
- [x] Deploy only RabbitMQ after explicit remote infrastructure approval and verify health, Windows AMQP reachability, and management access while preserving PostgreSQL/Garage (2026-10-09).

See [development setup and verification instructions](rabbitmq-development.md). This slice does not implement application integration or the ADR-0008 processing components.

## Phase 1B — PostgreSQL transactional outbox persistence

- [x] Add V12 outbox schema, JSONB payload versioning, delivery metadata, constraints, and indexes for future publisher scans.
- [x] Insert file/folder purge-intent events with Spring Data JPA inside the existing business transaction; reject standalone writer calls.
- [x] Add real PostgreSQL tests for atomic commit/rollback, uncommitted visibility, insertion failures, ownership/eligibility rejection, and repeated requests on both paths.

See [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md). Phase 1B covers persistence; Phase 1C adds publication and publisher recovery; Phase 2A adds job persistence; Phase 2B adds consumer ingestion; Phase 2C adds opt-in worker scheduling/renewal and supported-type recovery. Phase 2D.1 adds file byte purge; Phase 2D.2 adds folder execution; historical-intent backfill remains unimplemented. Test execution results belong in the task report.

## Phase 1C — Reliable outbox publication

- [x] Add V13 token/lease state and use PostgreSQL `FOR UPDATE SKIP LOCKED` for short concurrent claims.
- [x] Publish outside database transactions with stable event IDs, persistent messages, durable direct/quorum topology, correlated confirms, and mandatory-return checks.
- [x] Fence completion by unexpired token; bound retries/backoff and recover abandoned claims, retaining exhausted events as `FAILED`.
- [x] Add concurrent publisher, failure/expiry/replay, and simultaneous file/folder purge tests; isolate live broker tests from production topology.

See [publisher development/recovery instructions](outbox-publishing.md) and ADR-0008. Execution results belong in the task report. Polling is opt-in; worker execution, historical-intent backfill and job recovery remain separate phases.

## Phase 2 — Build a minimal reliable purge worker

Goal: remove Garage objects asynchronously and finalize metadata without pretending PostgreSQL and S3 share a transaction.

- Partially implemented ADR-0008 architecture: development RabbitMQ, atomic purge-request outbox persistence, leased publication, durable jobs, consumer ingestion, opt-in scheduling/renewal and trusted-writer file byte purge are present; folder purge and metadata finalization are now implemented by Phase 2D.2; Phase 2E.4 adds bounded aged-PUBLISHED missing-job reconciliation; broad legacy backfill remains planned.
- Consume at-least-once notifications idempotently; extend atomic outbox persistence to future business transitions where required.
- Have idempotent workers claim durable jobs in PostgreSQL; use scheduled database scans to recover missed notifications, retries, and interrupted work. Database polling is a recovery and publishing mechanism, not an alternative execution topology.
- Define safe job claiming for multiple worker instances, retry/backoff, attempt metadata, and terminal-failure handling.
- Treat object deletion as idempotent or explicitly handle an already-missing object.
- Delete/finalize the database row only after the storage outcome is known.
- Add tests for success, transient storage failure, process interruption, duplicate delivery, and concurrent workers.
- Add operational signals for pending age, retries, failures, and orphan detection.

Exit criteria: a committed deletion request survives restart, is retried safely, cannot be restored, and eventually reaches a documented terminal state.

### Phase 2A — Durable PostgreSQL job foundation (completed)

- [x] Add V14 stable event/job identity, typed aggregate references, lifecycle/attempt/lease timestamps, constraints and scan indexes.
- [x] Add idempotent transactional creation and independent short claim/complete/retry transactions with row locking and durable token fencing.
- [x] Persist ten-attempt budgets, 30-second leases, one-to-60-second exponential backoff, abandoned-claim recovery and terminal `FAILED` state.
- [x] Cover competing claims, duplicate creation, stale completion, lease recovery, rollback, backoff and exhaustion against PostgreSQL.
- [x] Document the [state machine and crash behavior](background-jobs.md) in ADR-0008 and architecture notes.

Phase 2A supplies persistence primitives; Phase 2B connects notification delivery to
job creation and Phase 2C adds scheduling/renewal. Phase 2D implements file/folder
purge handlers and metadata finalization. Stale-upload reconciliation and broad legacy backfill remain planned; Phase 2E.4 recovers eligible aged-PUBLISHED missing jobs. The overall Phase 2
exit criteria remain pending. Execution results belong in task reports.

### Phase 2B — RabbitMQ consumer integration (completed)

- [x] Add a disabled-by-default manual-ACK consumer for existing v1 file/folder purge events, without broker topology changes.
- [x] Validate strict envelopes and committed outbox identity; create/find the stable event-ID job in one short transaction before ACK.
- [x] Preserve job progress on duplicate/redelivered notifications, including commit-before-ACK loss.
- [x] Bound database retries to three attempts with one-/two-second delays; stop with unacked work on exhaustion for explicit restart/redelivery.
- [x] Add V15 sanitized PostgreSQL quarantine for permanently invalid messages; commit quarantine before ACK, retaining no raw payload/exception.
- [x] Cover PostgreSQL rollback/commit ordering and live isolated-vhost delivery, quarantine, failure/stop and redelivery.
- [x] Document [consumer configuration, acknowledgement and recovery](purge-consumer.md).

Phase 2B itself adds no job execution or Garage deletion. Phase 2D.1 supplies file
byte purge; upload reconciliation and broad legacy backfill remain unimplemented. Phase 2E.4 supplies bounded missing-job recovery.
Runtime startup remains disabled until explicitly enabled; verification does not
deploy the development API. Raw invalid-message recovery and outbox/job/quarantine
retention remain open; Phase 2C below adds supported-type scheduling and renewal.

### Phase 2C — Durable job recovery and worker foundation (completed)

- [x] Add opt-in fixed-delay claiming/dispatch for registered handler types, using existing PostgreSQL token/lease claims.
- [x] Reserve capacity before claiming; bound handler and renewal pools/queues and retain slots for interrupt-ignoring tasks.
- [x] Add live-token lease renewal that extends the persisted crash retry deadline without consuming attempts; preserve V14/V15 unchanged.
- [x] Enforce monotonic local lease/runtime limits and isolate renewal database calls from cancellation monitoring.
- [x] Stop admission on context closure, bound graceful waiting, interrupt unfinished work and leave leases recoverable.
- [x] Add a minimal explicit-result handler API, with no fake-success production handlers.
- [x] Cover concurrent schedulers, renewal/stale tokens, lock-wait expiry, crash/timeout recovery, capacity, slow renewal and shutdown against PostgreSQL.
- [x] Document [worker configuration and recovery](job-workers.md).

All scheduling stays disabled by default. Phase 2C added no storage handler;
Phase 2D below supplies file/folder execution and metadata finalization. Stale-upload
reconciliation, broad legacy backfill, retention/reset tooling and tuning remain open.

### Phase 2D.1 — File byte purge under trusted writers

- [x] Retain Garage 2.3.0 with the explicit trusted-writer/nonreplacement contract.
- [x] Add V16 immutable upload bindings, permanent key allocations and event-ID purge targets; do not infer legacy bindings.
- [x] Commit verified targets with existing purge intent and unchanged v1 outbox events.
- [x] Register the real file handler with bounded Garage I/O, object-absence success, cancellation and fenced worker completion.
- [x] Prevent repeated application writes to allocated keys and disable SDK retries; keep external I/O outside PostgreSQL transactions.
- [x] Cover atomic rollback, migration/legacy safety, duplicate/stale execution, failed retries, competing writes and unrelated/newer object preservation with isolated fixtures.
- [x] Document [storage assumptions and operational recovery](file-purge.md).

Workers and consumers remain disabled by default. Job success records the byte
deletion outcome only; Slice 2 below tracks standalone metadata finalization
separately. Phase 2D.2 completes eligible recursive folder purge. Independent writers can bypass
application protections; Garage does not
enforce conditional/version-specific deletion. Execution results belong in task reports.

### Phase 2D.2 — Shared progress and folder purge

- [x] Slice 1: reject restoration of a folder with its own pending purge.
- [x] Slice 2: V17 shared per-file identity and monotonic byte/finalization receipts;
  integrate standalone execution, conservative pending-folder guards and bounded
  explicit recovery of matching historical SUCCEEDED file jobs.
- [x] Slice 3: V18 bounded CONTINUE/checkpoint accounting; PostgreSQL migration,
  fencing/exhaustion, worker capacity/cancellation/shutdown and Garage regressions verified.
- [x] Slice 4: V19 durable paged discovery, atomic fenced checkpoints, sealed
  membership and standalone-finalization coordination.
- [x] Slice 5: registered folder handler, bounded shared byte deletion/never-written
  proof, member checkpoints and DELETE_FOLDERS transition.
- [x] Slice 6: V22 bounded bottom-up folder metadata removal, retained receipts,
  shared file finalization and atomic root/FINALIZED/checkpoint recovery.

See [the approved folder purge slices](folder-purge-design.md). A folder handler
is registered; scheduled finalization recovery remains absent. Slice 4 manifests are implemented. CONTINUE and its required
PostgreSQL/Garage verification are complete; live broker tests remained environment-gated.

## Phase 3 — Refine concurrency without weakening correctness

Goal: reduce unnecessary same-user serialization.

- Inventory every namespace-changing operation and its read/lock/write order.
- Extend deterministic PostgreSQL-backed concurrency coverage as new namespace and purge-worker paths are implemented.
- Establish a reproducible contention workload and baseline; do not infer performance from unit tests.
- Compare candidate lock scopes: parent-folder row, dedicated namespace lock row, PostgreSQL advisory lock, or optimistic write plus unique-constraint retry.
- Define stable lock ordering for operations spanning source and destination namespaces.
- Implement one bounded strategy and retain database uniqueness constraints as the final guard.

Exit criteria: correctness tests remain green and measured contention improves for unrelated namespaces. The exact target is pending baseline data.

## Phase 4 — Reconciliation and operability

- Reconcile stale `UPLOADING` rows and possible orphaned Garage objects.
- Add health/readiness checks that distinguish PostgreSQL and object-storage failures.
- Add structured logging, request correlation, metrics, and tracing.
- Define backup/restore and disaster-recovery procedures.
- [x] MVP logical owner quotas, atomic multipart reservations, usage API and bounded counter repair; see [quota operations](storage-quotas.md) and [ADR-0011](decisions/0011-storage-quota-accounting.md). Physical multipart reclamation and lifecycle retention remain open.

## Phase 5 — Sharing and authorization

- [x] Approve [the six-milestone sharing design](sharing-permissions-design.md).
- [x] Sharing M1: PostgreSQL grants, strongest-role inheritance, owner management,
  Viewer metadata/list/download, redaction and paginated shared entry points;
  [implementation and verification contract](sharing-m1.md).
- [x] Sharing M2: owner-owned subfolder creation, rename, immediate-parent-authorized
  trash and revocation/downgrade races. See [M2](sharing-m2.md).
- [x] Sharing M3: delegated multipart with initiator authority and revocation races;
  [M3 contract](sharing-m3.md).
- [x] Sharing M4: secure expiring/revocable file-only Viewer links and backend streams;
  [M4 contract](sharing-m4.md).
- [x] Sharing M5: immutable permission activity, owner-only paginated history,
  atomic outbox persistence and isolated routing; [M5 contract](sharing-m5.md).
- [x] Sharing M6: in-app sharing notifications, recipient isolation, idempotent
  permission-route delivery and generic obsolete history; [M6 contract](sharing-m6.md).
- Threat-model enumeration, confused-deputy, and link-token risks.

## Phase 6 — Transfer scalability

- [x] Phase 3B / M0: bounded live Garage 2.3.0 multipart capability characterization;
  [matrix, unsupported races and browser prerequisites](garage-multipart-capabilities.md).
- [x] Phase 3C / M1: durable session/allocation identity, idempotent reservation and
  permanent decision guards; [implemented contract](multipart-upload-sessions.md).
- [x] Phase 3D / M2A: backend initiation, immutable signed part authorization,
  URL refresh and bounded resume APIs; [implemented contract](multipart-part-transfers.md).
- [x] Phase 3E / M3: frozen manifest, one completion dispatcher, multipart receipt,
  READY and bounded explicit recovery; [implemented contract](multipart-completion-recovery.md).
- [x] M4: owner-scoped cancellation, single-assignment backend abort, explicit
  bounded expiry and conservative cancellation recovery;
  [implemented contract and retention runbook](multipart-cancellation-expiry.md).
- [ ] Unknown initiation reconciliation and multipart removal proof remain
  separate work. Autonomous cleanup is absent; browser release remains gated.

- [x] Browser multipart harness and exact-origin Garage CORS template, with
  protocol/proxy and backend regression verification;
  [implementation and runbook](browser-multipart-interoperability.md).
- [ ] Live browser interoperability: approved HTTPS/origin/CORS configuration,
  actual browser execution of the full scenario matrix, and bounded clock skew.
  Harness delivery does not establish browser release readiness.

- [x] Implement direct signed multipart parts, durable resumable state, checksum verification, explicit completion/cancellation/expiry.
- [ ] Prove unknown-allocation reconciliation and physical multipart reclamation independently.
- Add versioning only after its retention and quota semantics are decided.
- Add malware/content processing as asynchronous work if required.

## Phase 7 — Clients and deployment

- Next.js web client and React Native client.
- Containerized deployment and environment-specific configuration.
- CI that provisions PostgreSQL and S3-compatible dependencies.
- Production security review, secret rotation, rate limiting, and capacity tests.

## Explicitly undecided

- Broad legacy/blocked-intent recovery, operational tuning and event/job/quarantine retention within ADR-0008. Phase 2E.4 resolves bounded aged-PUBLISHED missing-job creation; file/folder purge execution is implemented. Publisher policy is decided in Phase 1C; job identity/states/fencing/retries in Phase 2A; consumer disposition in Phase 2B; bounded scheduling/renewal/shutdown in Phase 2C.
- Granular lock mechanism and namespace key.
- Hard-delete retention delay and user-visible cancellation policy.
- JWT key evolution, account-state controls and refresh-history retention policy.
- Later sharing milestones, quota notifications/plan policy, deployment platform, and performance targets.

## Phase 2E.4 bounded missing-job reconciliation

- [x] Independently opt-in, disabled-by-default aged-PUBLISHED event/job gap recovery.
- [x] Strict consumer identity validation plus conservative retained file/folder intent checks.
- [x] Bounded keyset pages, statement/lock timeouts, delayed eligibility and exact insert deduplication.
- [x] Preserve all existing jobs/progress/proofs; retain blocked records with sanitized counters.
- [x] PostgreSQL race/history/pagination/timeout and disabled-startup coverage; disposable Garage preservation control.
- [x] [Operational recovery contract](missing-job-reconciliation.md) and ADR updates.

Broad pre-outbox/blocked-history repair, quarantine disposition, retention, resets,
stale uploads/orphans and measured tuning remain separate work. Execution results
are reported with verification; no deployment or autonomous default is enabled.

## M5 multipart purge compatibility

Implemented: cancellation/expiry filename release; retained logical disposal for
unfinished multipart folder members; receipt-bound READY file/folder deletion;
late outcome retention and deterministic completion/purge ordering. See [M5 guarantees
and verification](multipart-purge-compatibility.md). Physical multipart block reclamation,
unknown-allocation repair, browser release and autonomous cleanup remain deferred.
M5 ends here; no later milestone is implemented by this change.

## Authentication sessions, logout and profile MVP

- [x] Thirty-minute access JWTs with unique jti and session identity.
- [x] PostgreSQL hashed refresh families, transactional rotation and family-scoped reuse revocation.
- [x] Refresh/logout APIs, secure cookie and CSRF contract, immediate access revocation and bounded jti cleanup.
- [x] Authenticated profile GET/PATCH with validated display names and secret-free DTOs.
- [x] PostgreSQL/HTTP concurrency, replay, isolation, failure, transport and profile checks.
- [x] [API, security, rollout and operational contract](authentication-sessions.md).

No frontend, browser multipart release or additional authentication features are
included. General rate limiting, account-state features, JWT key evolution and
refresh-history retention remain separate work.

## MVP API contract consistency

- [x] Fresh permission/lifecycle capabilities and immediate-parent Editor Trash eligibility.
- [x] Common owner Trash entries, timestamps, actions and pending-ancestor suppression.
- [x] Bounded folder/Trash pagination with deterministic order and snapshot counts.
- [x] Owner-only retained deletion-operation status and receipt-backed logical completion.
- [x] Common error/validation envelopes and preserved non-leaking public-link semantics.
- [x] [Response, compatibility, polling and operational documentation](api-contract.md).
- Verification results and gated limits are recorded in that milestone document.

This milestone ends here. Search, quotas, copy, new activity history, frontend,
retention policy and physical reclamation remain separate work.

## MVP Storage Usage & Quota Enforcement

- [x] V38 owner limits, backfilled used/reserved counters and immutable known allocation lengths; V39 account cleanup compatibility.
- [x] Atomic owner quota enforcement across direct multipart admission, READY, FAILED and metadata removal.
- [x] Authenticated owner usage API, generic quota error and separately inventoried uncertain storage.
- [x] Bounded explicit reconciliation and counter repair without provider-based reclamation inference.
- [x] PostgreSQL concurrency/lifecycle and live Garage regression coverage; execution results belong in the task report.

See [ADR-0011](decisions/0011-storage-quota-accounting.md), the [API contract](api-contract.md)
and [operator procedure](storage-quotas.md). Notifications, frontend, copy, search,
physical multipart reclamation and automatic quota cleanup are outside this milestone.

## MVP Filename Search & Filtering

- [x] Authenticated PostgreSQL filename substring search across files and folders.
- [x] Reuse effective owner/direct/inherited Viewer/Editor access, lifecycle barriers and private-parent redaction.
- [x] Literal SQL wildcard escaping, Unicode database case mapping, bounded type/MIME/size/date/owned-shared filters.
- [x] One-statement visible counts and stable bounded mixed-resource offset pages.
- [x] V40 active-name trigram indexes and bounded read timeouts, without measured speedup claims.
- [x] Isolated PostgreSQL/HTTP contracts and overlapping revoke/downgrade/move/Trash/restore/purge snapshots.
- [x] [Endpoint semantics](api-contract.md#filename-search-and-filtering-2026-10-22), architecture and learning notes.

Verification results and gated skips are recorded in
[architecture evidence](architecture.md#filename-search-verification).
Stop at this milestone. Content/full-text/semantic search, copy operations,
general activity history, frontend, arbitrary sorting and cursor pagination
remain outside this implementation. Broad-query capacity and latency are unmeasured.
