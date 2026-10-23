# MVP backend API contract freeze — 2026-10-23

Status: **backend contract frozen for frontend implementation; browser release
and deployment readiness remain gated**. This milestone stops before Next.js
implementation. No infrastructure, applied migration or storage protocol changes
are part of the freeze.

## Reviewed surface and acceptance boundary

The [endpoint inventory](api-endpoint-inventory.md) enumerates **73 operations on
63 paths**, including concrete file/folder grant paths. The [OpenAPI 3.1
artifact](openapi.json) supplies 61 record schemas, parameters, defaults,
authentication, authorization descriptions, success/error responses and
operation-state enums. The [frontend guide](frontend-integration.md) supplies
sequencing, navigation/redaction, retries and recovery.

| Area | Frozen MVP surface |
|---|---|
| Authentication/profile | Registration/login, cookie rotation/CSRF, authenticated logout, GET/PATCH profile |
| Navigation/metadata | Root/child folders, separate files page, metadata/capabilities, rename/move, Shared with me, redacted parent traversal |
| Trash/deletion | Common-entry Trash pages, restore fallback/name, 202 operation identity and owner-only logical-completion polling |
| Multipart | Idempotent admission, single initiation, immutable part signing/evidence/marker pages, complete/recover, cancel/recover/status, expiry |
| Empty/copy | Dedicated zero-byte admission/status/recovery; file/folder copy status/execute/recover/cancel and separate folder discard |
| Sharing | Registered-email Viewer/Editor grants, ETag/If-Match updates, expiring/revocable header-token file links |
| Discovery/history | Filename/filter search, resource/permission history, recipient notifications/count/read, owner logical quota |
| Operations | Public liveness/readiness, bounded admission/retry hints, retained blocked/unknown/failure semantics; no HTTP admin |

Controllers, DTOs, security/filter/advice, services, lifecycle migrations and
integration tests were reviewed against architecture/roadmap, feature contracts
and the relevant ADRs 0002–0016. Documentation was not treated as execution
evidence. PostgreSQL/Garage/RabbitMQ tests remain the behavioral authority for
storage, concurrency, quota, notification delivery and positive recovery proof.

No missing endpoint requires expansion for the bounded MVP workflows. The frontend
must retain operation IDs/keys, use separate file/folder feeds, registered-email
grant entry and caller-driven copy recovery. The absence of an operation-list,
directory or combined navigation endpoint is explicit; it never authorizes
private-resource inference or storage redispatch.

## Corrections and compatibility

1. Unsupported requested response media now returns **406 NOT_ACCEPTABLE** with
   sanitized JSON. The unexpected-exception fallback could misclassify it as 500.
   No successful JSON response shape changed.
2. Auth docs describe the existing common JSON 400/403 transport errors,
   superseding the stale empty-response description.
3. Empty-file/API/ADR wording distinguishes authenticated declared MIME from
   public-link **application/octet-stream** downloads. Runtime public MIME was
   already fixed and was not changed here. The browser runbook also clarifies
   that its delegated label is computed from ownerId, not a Session DTO property.
4. The handoff distinguishes session decision state, completion/cancellation
   disposition, copy publication, empty creation and logical deletion. There is
   no invented universal async state or unsafe retry conversion.
5. Same-origin HTTPS Next.js `/api` proxy is the supported API topology. No backend
   credentialed cross-origin policy is added. Garage PUT CORS is independently gated.

Earlier envelope/page/Trash corrections remain the baseline; historical
message-only errors and array folder feeds are unsupported. POST `/api/v1/files`
remains retired (authenticated 405, anonymous 401), without nonempty synchronous
fallback. Secrets never move into query parameters/JSON. No other breaking runtime
change is included.

Changed files: the runtime `GlobalExceptionHandler`; test-only `OpenApiDocument`
and `ApiContractFreezeIntegrationTest`; the new OpenAPI/inventory/frontend-guide/
freeze artifacts; and existing API contract, architecture, roadmap, authentication,
browser interoperability, operational hardening, learning notes and ADR-0016.
Deployment guidance now names the current V46 schema boundary.

## Artifact generation and maintenance

`OpenApiDocument` is a **test-only** exporter of the running MVC mapping registry,
method parameters and reachable record types. It expands regex grant mappings,
unwraps ResponseEntity, models nullable fields/mixed resource unions, and adds
reviewed status/security/validation/state/header metadata. That metadata is a
contract decision, not a guarantee inferred from a return type. Feature-specific
geometry/date/evidence rules remain in linked prose. Common transport errors are
listed conservatively, not as a promise of every business failure at every route.
Binary streams cannot emit JSON after headers commit. No runtime docs dependency,
public `/v3/api-docs`, Swagger UI or new infrastructure is introduced.

`ApiContractFreezeIntegrationTest` compares generated JSON/Markdown with committed
artifacts, checks operation IDs and validates representative actual HTTP bodies
against the exported schemas. Ordinary tests **do not update** the freeze.
Intentional regeneration from `backend/`:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=ApiContractFreezeIntegrationTest' '-DupdateApiContract=true' test
```

That test owns a UUID schema in `vaultdrive_test`. Review artifact/metadata changes
before committing. Broader tests must use fresh default-context schemas. Response
record properties are present, including nullable values; optional request
parent/MIME/expiry fields may be omitted. Schema checks supplement domain/race
tests; they do not prove every branch, browser behavior, load, multi-node failure
or physical reclamation.

## Deferred changes and unresolved launch blockers

- **Real-browser HTTPS/CORS/clock matrix remains BLOCKED/NOT EXECUTED** under the
  [2026-10-23 record](browser-multipart-verification-2026-10-23.md). Trusted HTTPS,
  approved exact-origin Garage CORS, real cookie/framing/header/native-expiry/
  recovery/download/revocation evidence and sustained clock bounds remain required.
- Unknown initiation/abort and some Complete/Copy/empty PUT outcomes can remain
  indefinitely. Negative probes, expiry, cancellation and logical completion do
  not establish physical reclamation. Physical-capacity/admission and
  retention/erasure policy remain launch prerequisites.
- Deployment processors, edge/fleet abuse protection, trusted-writer retirement,
  credential/key rotation, restore-tested backup/disaster recovery and measured
  capacity remain in [operational hardening](operational-hardening.md).
- Cross-origin credentialed APIs, account management/directory/global logout,
  combined/breadcrumb/operation-list discovery endpoints, arbitrary sorting/cursors,
  push notifications, Range downloads, autonomous copy, broad unknown-effect
  repair and OpenAPI serving are deferred, without implied compatibility promises.

Future incompatible field/state/status/auth/pagination changes require an explicit
compatibility/versioning decision plus artifact, feature-contract, test and guide
updates. The freeze adds no automatic version negotiation or compatibility shim.

## Executed verification

Fresh-schema focused selection: **121 tests passed**, zero failures/errors/skips.
The expanded contract selection passed **eight tests**, zero failures/errors/skips.
Full safely runnable backend regression finished **2026-10-23 17:57:27Z**:
**1,587 tests; 1,566 passed, 21 skipped, zero failures/errors**. It included all
**27** enabled live Garage multipart/evidence checks, **eight** Garage copy/empty
object cases and the isolated RabbitMQ regressions. After final export metadata
review, the single export test passed and the **non-updating eight-test contract
selection passed** at **17:59:23Z**, with zero failures/errors/skips. The runtime
code was unchanged after the full regression.

Independent JSON Schema 2020-12 meta-validation passed for all **61 components**
and **1,144 inline schema positions**; internal references, operation-ID uniqueness,
all path parameters, updated Markdown file/anchor targets and Git whitespace checks
passed. The final listener inspection found no localhost 5672 listener. Build
logs/reports are ignored under `backend/target`; no browser evidence is inferred.

The full run used default-context schema `api_freeze_full_20261023` and the
focused selection used `api_freeze_focused_20261023`, with the three datasource/
Flyway schema overrides from the [existing verification procedure](browser-multipart-verification-2026-10-23.md#repository-verification-for-this-recheck).
Suites with independent UUID schemas retain their own isolation. The private
environment was loaded in each Maven shell. The full command was:

```powershell
.\mvnw.cmd -o '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=api_freeze_full_20261023' '-Dspring.flyway.schemas=api_freeze_full_20261023' '-Dspring.flyway.default-schema=api_freeze_full_20261023' test
```

The existing four safe `GARAGE_MULTIPART_{TRANSFER,COMPLETION,CANCELLATION,PURGE}_TEST`
flags and `GARAGE_COPY_TEST` were true, with version attestation `2.3.0`. A
task-owned loopback-only AMQP SSH forward reached the existing isolated
`vaultdrive_outbox_test` vhost and was closed in `finally`. No broker policy,
shared deployment configuration or credentials changed. All normal production
processors/probes remained disabled. Fresh verification schemas are retained;
existing tests clean up only their own disposable objects/topology/fixtures.

| Gated suite | Skipped | Requirement not enabled |
|---|---:|---|
| AsyncPurgeEndToEndTest | 1 | ASYNC_E2E_TEST and the separate continuous end-to-end runbook |
| AsyncPurgeProcessRecoveryTest | 1 | ASYNC_PROCESS_TEST and approved process-kill procedure |
| GarageMultipartCapabilityTest | 18 | GARAGE_MULTIPART_TEST and separate M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | GARAGE_MULTIPART_REVIEWED_RUN with a reviewed retained UUID |

These are explicit gated skips, not passed checks. Real-browser execution is a
separate unresolved gate and contributes no backend test passes.

An initial broader focused invocation against the historical default test schema
hit the documented V26 drift (database 1493870529, repository -713442801); no repair
or migration edit was made. A first new-test assertion exposed MockMvc's empty
servletPath; the test now supplies the real auth path so admission executes.
