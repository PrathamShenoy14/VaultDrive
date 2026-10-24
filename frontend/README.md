# VaultDrive frontend M1–M4

Next.js App Router, real backend authentication and a responsive Drive-inspired file explorer. M2 adds navigation, selection, metadata, search, Shared and Trash; M3 adds transfers; M4 adds folder creation, rename, move, Trash/restore, permanent-deletion tracking and owner-only file/folder copy. The frozen [OpenAPI contract](../docs/openapi.json) and backend remain authoritative. No backend runtime, migration or infrastructure changes are included.

## File and folder management

Create folders in My Drive or a folder with `CREATE_FOLDER`. Row/grid/context menus and single-selection toolbars share capability-aware Rename, Move, Copy, Trash, Restore and Delete permanently actions. Base UI dialogs validate Java-compatible names and file extensions, guard repeated submissions and restore keyboard focus. The paginated destination picker browses owned folders, excludes the selected source subtree and requires explicit destination selection. The backend rechecks every permission, cycle, collision, quota and lifecycle condition.

Management operations retains copy keys, normalized original requests, IDs and uncertain mutation evidence in user-scoped sessionStorage. Copy continuation and recovery are explicit; folder copy advances at most four backend steps per click with the frozen 1,000-member/depth-64 limits. Status-only polling backs off from two to 30 seconds, stops after ten reads, and cleans up on workspace exit. Accepted deletion is distinct from logical completion and physical cleanup. Reload dispatches no mutation; Check status or Replay original admission recovers the existing operation. Lost ordinary mutation/deletion responses are never automatically replayed. See [M4 behavior, contracts, recovery, verification and limitations](../docs/frontend-m4-management.md).

## Uploads, downloads and transfer management

Use Upload files or drop files into My Drive / a freshly editable folder. The workspace transfer panel survives navigation, shows per-file/overall provisional progress and provides Cancel, Check status, safe explicit Retry/Resume and Recover. It reserves at most 50 panel entries and runs two files with one 8 MiB slice each; files are limited to 5 GiB. A user-scoped sessionStorage journal retains keys/session IDs and re-selection metadata, never bytes, signed URLs or credentials. Reload requires the original file. Empty files use `/files/empty`; READY alone completes a transfer. INITIATING, blocked and uncertain completion/cancellation remain visible; neither cancel nor logical quota release promises physical Garage reclamation.

File menus and the single-file selection toolbar provide native downloads through the authenticated same-origin BFF stream. The BFF preserves MIME/disposition/length, including zero, and streams without buffering large files. Chrome owns download completion/interruption; retries are explicit, with no Range contract. Native errors open a sanitized status page in a separate tab. See [M3 architecture, state transitions, limits, security and verification steps](../docs/frontend-m3-transfers.md).

Direct browser multipart requires an approved trusted HTTPS Garage binding and exact page-origin CORS exposing ETag/checksum. The current HTTP storage/denied preflight still blocks real browser multipart. Mocked Chrome scenarios never close that gate. Local HTTP browser mutations fail the configured HTTPS Origin check by design.

## Run locally

Use Node.js 24 LTS (minimum 22.12) and npm. Backend and frontend build independently.

```powershell
cd frontend
npm ci
Copy-Item .env.example .env.local
# Set BACKEND_URL and the exact approved HTTPS APP_ORIGIN in .env.local.
npm run dev
```

The backend normally listens on `http://127.0.0.1:8080`; start it using the backend's existing private environment and development instructions. All normal autonomous processors remain disabled. The frontend reads only server-side environment variables:

| Variable              | Purpose                                                                                                                      |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `BACKEND_URL`         | Trusted HTTP(S) backend origin, no path/credentials/query. Never exposed to browsers.                                        |
| `APP_ORIGIN`          | Exact frontend HTTPS origin, no trailing slash. Used for BFF mutation CSRF admission; never inferred from forwarded headers. |
| `SESSION_MODE`        | Required literal `single-process`. M1 needs one long-lived Node process.                                                     |
| `SESSION_MAX_ENTRIES` | Bounded in-memory sessions, default 10000; expired entries are pruned on login. Capacity exhaustion returns 503.             |

The checked-in example contains no credentials. Environment validation runs at startup/build; even a production build needs these values. No `NEXT_PUBLIC_*` configuration or JWT signing key is required.

Local HTTP serves public pages and supports layout testing. Browser authentication requires the approved same-origin HTTPS topology. HTTP preview does not relax Origin checks, Secure cookies or `__Host-` cookie rules. No TLS, DNS, VM, Garage, CORS or firewall configuration is provisioned here. `dev:https` is an optional Next.js development command; development certificates do not establish the existing trusted-HTTPS release gate.

## Structure and routes

```text
src/app/                       App Router pages, loading/error states, API route
src/components/ui/             shadcn base-nova / Base UI controls
src/components/                Branding and theme providers
src/features/auth/             Forms, validation and server-only BFF/session code
src/features/shell/            Navigation, account menu, storage and activity placeholder
src/features/explorer/         Feeds, selection, metadata, breadcrumbs and search
src/features/transfers/        Upload manager, direct part transport, recovery and downloads
src/lib/api/                   Generated types and typed lossless JSON transport
src/lib/env.ts                 Server environment validation
scripts/generate-api.mjs       Reproducible contract generation/drift check
tests/                        Isolated transport/session/UI tests
tests/e2e/                    Chrome UI + real backend HTTP integration checks
```

- `/` redirects to `/drive`.
- `/login`, `/register` provide real backend-connected forms. Registration requires a subsequent login.
- `/drive` lists owned root folders/files; `?folder=<uuid>` opens accessible nested contents.
- `/shared` lists real direct shared entry points; `?folder=<uuid>` traverses accessible children.
- `/search?q=<filename>` searches owned/effectively shared resources with documented filters.
- `/trash` lists independent bounded folder/file Trash feeds with lifecycle details.
- `/activity` remains a deliberate placeholder. All workspace pages retain server-side authentication.
- `?view=list|grid` selects the view on explorer routes. Folder/search/filter/view state survives reload and browser history; selection and accumulated pages are local to a mounted scope.
- `/session/restore` posts to the sole BFF refresh coordinator, then reloads `/drive`. It never receives tokens.
- `/api/v1/[...path]` allows POST `auth/register|login|refresh|logout` and GET `users/me|users/me/storage`, `folders`, `folders/{uuid}`, `folders/{uuid}/children`, `files`, `files/{uuid}`, `shared-with-me`, `search`, `files/trash`, `trash/folders`. M3 additionally allowlists multipart admission/initiate/sign/complete/recover/cancel, session/parts/cancellation reads, empty-file admission/status/recovery and `files/{uuid}/download`. Only documented query names are forwarded. Duplicate, unknown and oversized queries are rejected; domain validation and authorization remain in Spring. Unlisted paths return 404 and unsupported resource methods return 405.

Server Components own protected profile/quota reads; Client Components handle forms, theme, dialogs, menus and responsive navigation. M2 keeps scope-local, abortable read state rather than introducing TanStack Query/Table: independent feeds, explicit page loading and native checkbox/keyboard interactions do not yet need a shared resource cache or table framework. React Hook Form uses Zod validation and field errors without an additional resolver adapter. Tailwind semantic tokens support light/dark themes and future file grids/tables. Base UI provides focus management and keyboard behavior. `next-themes` stores only a theme preference in localStorage.

## Explorer behavior and contracts

Folder/file feeds request **50 entries each**, with independent totals and explicit Load more controls. No global combined total is invented. The displayed order is the backend's fixed name order (Trash: deletion date descending); sorting controls deliberately expose no unsupported options and never submit `sort`. New pages are deduplicated by resource type plus UUID because concurrent offset snapshots can shift. Refresh rereads loaded pages and server quota, preserving URL state and eligible selection. M3 transfers and M4 mutations invalidate current feeds; other locations reread on navigation.

Click a checkbox or row to select; Ctrl/Command click toggles and Shift click selects a range within a feed. Ctrl/Command+A within a resource group selects its loaded items; Escape clears selection. Arrow Up/Down, Home/End move row focus, Space toggles, Enter opens folders or metadata, and Shift+F10/right click opens the same Base UI action menu as the visible actions button. Folder names also provide an explicit open control for touch/keyboard users. List/grid switching preserves selection. Details use a fresh metadata GET and Base UI's focus-managed sheet; unavailable/revoked metadata renders an error rather than a stale snapshot.

Search debounces at 400 ms and supports `resourceType`, `scope`, `mimeType`, `minSizeBytes`, `maxSizeBytes`, `createdFrom`, `createdTo`, `modifiedFrom`, `modifiedTo`. Filters combine with AND; MIME/size with FOLDER is rejected by the backend. Inclusive dates use ISO 8601 instants with timezone. Clear search returns to My Drive; Reset filters keeps the query. Searches have independent bounded incremental pages, empty states, explicit retry and timeout handling. Scope changes abort old reads and discard their results.

Shared reads display actual Viewer/Editor roles and use returned capabilities for read and management actions. Backend authorization remains authoritative. Breadcrumb traversal reads only returned parent IDs, stops at null or inaccessible ancestors, and is cycle/depth bounded. Shared is an entry-point link, never an inferred owner's root. Search folders open Shared or My Drive according to current metadata/capabilities; file results open metadata, not an inferred private location. Trash displays only its common-entry DTO, without active metadata calls or private-parent inference. Restore/permanent deletion require their returned capabilities; pending purge entries expose neither action.

Loading skeletons, per-feed empty/error states, no-store transport, explicit retries and authorization failures are covered by mocked tests. The client read deadline is 15 seconds, the server backend deadline 10 seconds. No automatic retry loop or cross-session cache is introduced. Server-held credentials and the existing single-process refresh coordinator are unchanged.

## Authentication and proxy design

See [ADR-0017](../docs/decisions/0017-frontend-bff-sessions.md). The browser gets an opaque random 256-bit `__Host-vaultdrive-session` cookie: Secure, HttpOnly, SameSite=Strict, Path=/, no Domain. Access JWTs, the session CSRF secret and the current backend cookie jar remain in server-only process memory. They are never serialized in HTML/RSC, JSON, URLs, analytics, browser storage or application logs. The backend's Secure/HttpOnly refresh cookie is also forwarded unchanged to the browser, but the BFF always uses its authoritative server-held jar, ignoring stale browser refresh cookies and supplied Authorization/CSRF headers.

Login sends exactly `X-VaultDrive-Client: 1` to Spring. Backend login/refresh returns a JWT and CSRF header; the BFF consumes them and returns **204**, deliberately differing from the backend's 200 token response. Registration and profile/quota JSON remain backend responses. The browser helper's types model this separate BFF boundary explicitly; generated types describe the backend.

Every BFF mutation requires the exact configured Origin and `X-VaultDrive-Client: 1`, rejects cross-site/same-site Fetch Metadata, and requires documented bounded JSON/no-body requests. Backend cookie mutations use exactly its stored CSRF header. Refresh omits Authorization and queries; logout includes the latest bearer, refresh cookie and CSRF. Cookie headers use `Headers.getSetCookie()` and individual append operations, including multiple cookies with comma-containing Expires attributes. No comma splitting or attribute weakening occurs. Ordinary backend requests are bearer-only. Responses/fetches are no-store, personalized pages are dynamic, and login/logout use full navigations to discard the Router Cache. M3 adds transfer routes; M4 adds the frozen namespace, copy and deletion-operation routes. Preview and sharing management remain closed. Streaming downloads release the session lock after upstream headers.

The backend's `expiresIn` controls access expiry (normally 1800 seconds). A 15-second early-refresh margin reduces clock-boundary failures; it does not establish monitored clock correctness. The browser's opaque session has at most seven days, and server expiry can only decrease with the backend's remaining cookie lifetime. Rotation never extends the family. An async per-session lock covers refresh, protected reads and logout across all tabs/requests in the one supported Node process. Waiting callers reread the session and use its latest credentials; duplicate refresh POSTs do not rotate an already-fresh token. Logout is serialized with them. No mutation is automatically retried. A backend 401 for an unexpired bearer destroys local state rather than trying to refresh a revoked family.

RSC reads authenticate against `/users/me` on the server; each page and each data read has its own guard rather than relying solely on a layout. Expired access redirects to the restore page because RSC rendering cannot write cookies. No middleware, Server Action, render or prefetch starts a separate rotation. Missing/expired/revoked/logged-out sessions lead to login. Restore lands at `/drive`, without arbitrary return URLs.

Any lost/incomplete/failed refresh response abandons local credentials and requires login; it is never retried. A received 429 retains the session because backend admission precedes business work, preserves Retry-After and requires an explicit later attempt. Other 5xx rotations are treated as uncertain. Only committed backend 204 logout clears the backend refresh cookie; failed logout preserves the backend response. A server restart loses local sessions and fails closed. Replacing a login or losing local state does not assert backend-wide revocation; orphaned backend families retain their fixed expiry. This is not global logout.

Backend error envelopes `{status,error,message}` and Retry-After pass through unchanged. The typed client retains both and presents status-based messages for 400/401/403/404/409/429/5xx without parsing messages. Network failures become sanitized 502; failed protected reads render an error/retry boundary, and quota failure has an explicit retry state. No automatic mutation retry/backoff loop exists.

## Generated types and integer precision

```powershell
npm run api:generate
npm run api:check
```

`openapi-typescript` reads `../docs/openapi.json`. Never edit `src/lib/api/generated.ts` manually. The deterministic transform maps int64 to `number | bigint`; `lossless-json` preserves unsafe integers as bigint at the transport boundary. The quota reader normalizes every quota counter to bigint; explorer file sizes and page totals also retain int64 precision. Search byte filters remain decimal strings through the BFF, without a Number conversion. Storage formatting/percentage arithmetic stays exact before its bounded presentation conversion. Large values are never silently rounded. TypeScript 5 and ESLint 9 are pinned to the newest mutually supported peer lines of openapi-typescript and Next.js's bundled lint plugins; incompatible newer majors are deliberately excluded. The lockfile pins the resolved stable package set.

## Verification

```powershell
npm run api:check
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build
npm start
```

Vitest isolates backend fetches to cover envelope/status/retry behavior, lossless quota, credential boundaries, multiple Set-Cookie headers, custom-header/Origin admission, registration/login/logout, restoration, expiry/revocation, absolute lifetime, concurrent rotation and protected server redirects. UI tests cover form labels/errors, visibility control, navigation and quota. These are isolated tests, not backend end-to-end evidence.

`npm run test:e2e` expects a built frontend on `http://localhost:3000`, backend reachable through `BACKEND_URL`, and Google Chrome installed. Override `E2E_BASE_URL` and `E2E_APP_ORIGIN` as needed. Use only a fresh disposable test schema with all workers off: the suite registers UUID-addressed test users and leaves those test rows/schema retained. It must not target production/shared user data. The backend can be started with the existing ignored private environment and fresh `spring.datasource.url`/Flyway schema overrides, as in the existing backend verification runbooks; no secrets belong in frontend config.

The Chrome suite checks desktop, emulated phone and tablet layouts, keyboard dismissal/focus, account controls, themes, no horizontal overflow and axe accessibility. Registration/login/profile/quota/logout are also exercised through real BFF HTTP calls against Spring/PostgreSQL. **The HTTP test client supplies the configured HTTPS Origin and bootstraps an HttpOnly session into Chromium using localhost's Secure-cookie exception. This is not a real-browser login/logout or trusted-HTTPS cookie/CSRF proof.** Refresh rotation/expiry/revocation are covered by isolated BFF tests and the backend's real session integration suite, not a 30-minute browser session-expiry run. Screenshots and reports are ignored. Traces are disabled to avoid retaining credential-bearing network traffic.

Relevant backend checks (from `backend/`, private environment loaded in the same shell):

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=AuthControllerTest,AuthenticationSessionIntegrationTest,ApiContractFreezeIntegrationTest' test
```

The M2 selection also includes `SearchIntegrationTest`, `TrashControllerTest` and `ApiContractConcurrencyIntegrationTest`. Supply fresh default-context datasource/Flyway schemas, as in the verification record below; suites with their own UUID schemas retain that isolation. No backend production code changed, so full storage/purge/broker regression is outside this frontend slice.

The M2 Chrome scenario uses existing backend APIs **only for disposable fixture setup**: registered users, 50+ root folders, nested/shared folders, direct Viewer/Editor grants, real zero-byte files and trashed entries. Zero-byte fixture creation requires reachable Garage. No fixture mutation API is added to the BFF or product UI. Fixture schemas/accounts/objects are retained; no shared user data, infrastructure policy, restore or permanent deletion is changed. Test traces remain disabled.

## Executed M4 verification — 2026-10-24

Final checks passed on the existing Node 24.22 runtime: API generated-type drift, TypeScript, ESLint (zero warnings), Prettier, **263 Vitest tests** and the production build. The focused backend selection passed **369 tests** and the separate real Garage copy suite passed **8 tests**, with zero failures/errors/skips. No full unrelated broker/storage regression was claimed.

Chrome passed **6 M4 scenarios** across desktop, phone and tablet: **3 real HTTPS browser/BFF/backend workflows** and **3 mocked management workflows**. Actual browser forms registered/signed in, the BFF cookie remained Secure/HttpOnly/Strict, and real UI actions created/renamed/moved/trashed/restored folders, published independent file/folder copies and tracked accepted deletion. The zero-byte Garage fixture used an authenticated BFF HTTP client. Light/dark screenshots, keyboard focus return and destructive-dialog axe checks passed. Mocked admission-loss/UNKNOWN/recovery scenarios prove UI behavior only. Autonomous purge workers were disabled; physical cleanup and browser-to-Garage multipart were not verified.

Use the existing trusted local certificate files without altering trust or bypassing TLS:

```powershell
npm exec next -- dev --experimental-https --experimental-https-key certificates/localhost-key.pem --experimental-https-cert certificates/localhost.pem
# In a second shell, with the same supported Node runtime:
$env:NODE_USE_SYSTEM_CA = '1'
$env:E2E_BASE_URL = 'https://localhost:3000'
npm run test:e2e -- m4.spec.ts
```

The real backend used fresh `frontend_m4_live_verified_20261024` in `vaultdrive_test`, with all normal processors/probes off. Synthetic accounts, schemas and retained asynchronous evidence remain; no shared user resources were touched. Package current backend sources before starting its JAR: an older local JAR initially returned 405 for the frozen empty-file fixture endpoint. An initial Garage invocation failed the version-attestation precondition; the corrected run below passed all eight checks. Earlier browser iterations caught restore-location/focus issues, a development hot reload during a status assertion and a device-default theme assumption; final reruns passed. Initial Node 20/sandbox worker startup failures were resolved using existing Node 24 and approved local worker execution.

Backend reproduction from `backend/`, loading the existing private environment in each shell:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=FolderNameValidatorTest,FileNameExtensionResolverTest,FolderServiceTest,FileMetadataServiceTest,FolderControllerTest,FileControllerTest,TrashControllerTest,FileCopyIntegrationTest,FolderCopyIntegrationTest,ApiContractFreezeIntegrationTest,SharingIntegrationTest,ApiContractConcurrencyIntegrationTest,FolderPurgeMetadataIntegrationTest,FilePurgeIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m4_verification_20261024' '-Dspring.flyway.schemas=frontend_m4_verification_20261024' '-Dspring.flyway.default-schema=frontend_m4_verification_20261024' test
$env:GARAGE_COPY_TEST = 'true'
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
.\mvnw.cmd -o '-Dtest=GarageFileCopyIntegrationTest' test
```

Tests with independent UUID schemas retain their existing isolation. Repeated runs should choose a fresh default schema. The original multipart HTTPS/CORS/clock matrix, cross-device operation discovery, uncertain storage repair and physical retention/erasure remain open. See [the M4 contract](../docs/frontend-m4-management.md) for exact routes and recovery behavior. Stop after M4; M5–M7 are outside this change.

## Executed M3 verification — 2026-10-24

Node **24.22.1**: API drift, strict TypeScript (including route type generation), ESLint, Prettier, **173 Vitest tests** and the production build passed. No dependencies, backend runtime/migrations, or frozen API/generated types changed. The upload button uses an opaque hover color and no disabled-to-enabled fade after the full Chrome scan exposed transient low contrast during hydration; the corrected complete suite passed. Request-scoped 429 rollback preserves any earlier uncertain dispatch; regression tests first reproduced lost evidence after status-read and admission-replay rate limits, then passed with the fix. Closing the workspace also fences new control dispatches after pending reads.

Evidence is separated as follows:

1. **Mocked frontend:** Vitest covers single/multipart slicing, two-file concurrency, progress, queued/in-flight cancellation, exact evidence, safe URL refresh/retry, successful original-file reload/resume, mismatched files, lost admission/initiation/completion/abort responses, empty READY/recovery/FAILED/REMOVED, bounded ten-GET polling, unfamiliar completion states, quota/permission/auth failures, BFF CSRF/admission, no automatic control retries, zero downloads and cancellation/backpressure of a declared five-GiB stream. **Three additional mocked Chrome scenarios** exercise actual browser slicing/hash/XHR, multiple files, unknown completion, explicit recovery and navigation persistence. Their API/storage responses are intercepted; they prove no Garage interoperability.
2. **Backend:** **357 tests passed**, zero failures/errors/skips (317 transfer/storage/contract cases plus 40 session/security/download cases). This includes real PostgreSQL quota/permission/race/recovery tests and **31 enabled live Garage multipart/receipt/cancellation/copy/empty cases**. Backend unit/controller/adapter cases are included in that total; not every backend case is an integration test. The full unrelated broker/purge/process suite was not rerun for this frontend change.
3. **Real browser / actual Garage:** Chrome **156.0.8078.47**, desktop, emulated phone and tablet: **18 total M1–M3 scenarios passed**, including **three M3 native-download scenarios**. Each downloaded real Garage empty and **16 MiB + 37-byte** files through the authenticated BFF, preserving names/zero MIME/length and matching whole-file SHA-256. Fixtures were created through actual HTTP APIs; multipart fixture PUTs used Node, not Chrome. Session bootstrap supplied the configured HTTPS Origin through the HTTP test client. Real HTTP browser upload admission failed closed. Scanned light/dark/mobile pages had zero axe violations and no horizontal overflow; screenshots were reviewed. This does not prove trusted-HTTPS browser login or direct multipart.
4. **Blocked/unverified:** Actual Chrome-to-Garage multipart remains **BLOCKED/NOT EXECUTED**. Current signing binding is HTTP on localhost:3900; no trusted page/storage TLS configuration was established, and the exact page-origin checksum PUT preflight returned 403 without required allow/expose headers. Native signed URL/session expiry, browser Garage interruption/cancellation/completion-loss/revocation, trusted-HTTPS downloads/auth and sustained browser/JVM/PostgreSQL/Garage clock bounds remain open. A five-GiB declared mock is not a five-GiB download/capacity measurement. See [prerequisites and continuation](../docs/frontend-m3-transfers.md#reproduction-and-remaining-gates).

The live backend used fresh schema `frontend_m3_live_20261024` in `vaultdrive_test`, with every normal autonomous processor/probe explicitly off. Synthetic browser accounts/objects and verification schemas are retained. Tests clean up only their own disposable fixtures where their existing runbooks permit it; retained ambiguous allocations were not adopted or swept. No VM, Garage CORS, DNS, certificate, firewall, port or shared infrastructure setting changed.

Reproduce the backend selections from `backend/` (the two schema names below were the executed default-context overrides; suites with UUID schemas retain their isolation):

```powershell
. .\.env.local.ps1
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
.\mvnw.cmd -o '-Dtest=MultipartSessionIntegrationTest,MultipartTransferIntegrationTest,MultipartCompletionIntegrationTest,MultipartCancellationIntegrationTest,DelegatedMultipartIntegrationTest,EmptyFileIntegrationTest,StorageUsageIntegrationTest,FileControllerTest,ApiContractFreezeIntegrationTest,GarageMultipartTransferIntegrationTest,GarageMultipartCompletionIntegrationTest,GarageMultipartCancellationIntegrationTest,GarageFileCopyIntegrationTest,GarageMultipartCompletionEvidenceTest,S3MultipartStorageTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m3_verification_20261024' '-Dspring.flyway.schemas=frontend_m3_verification_20261024' '-Dspring.flyway.default-schema=frontend_m3_verification_20261024' test
.\mvnw.cmd -o '-Dtest=SharingDownloadIntegrationTest,AuthControllerTest,AuthenticationSessionIntegrationTest,ApiContractConcurrencyIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m3_security_20261024' '-Dspring.flyway.schemas=frontend_m3_security_20261024' '-Dspring.flyway.default-schema=frontend_m3_security_20261024' test
```

`npm run test:e2e` runs all 18 Chrome checks; `npm run test:e2e -- m3.spec.ts` runs the six M3 scenarios, three live-download and three mocked-transfer. Do not combine those categories into a browser Garage upload claim.

## Executed M2 verification — 2026-10-24

Repository-local Node **24.22.1**: generated API drift check, strict TypeScript, ESLint, Prettier, **94 Vitest tests** and production build passed. No dependencies or backend runtime/migrations changed. The isolated tests cover independent paging/retry, fixed ordering, selection, keyboard/menu/focus behavior, all filter controls, lossless sizes, denied metadata, redacted/bounded breadcrumbs, Trash, timeouts, StrictMode and cancellation/stale responses. These are mocked component/BFF tests.

**12 Chrome scenarios passed** on desktop, emulated phone and tablet, including the three live M2 scenarios against Spring/PostgreSQL/Garage fixture APIs. They verify folder navigation/breadcrumbs, back/forward/reload, independent incremental pagination, list/grid selection, fresh file metadata/focus return, debounced/filter search, clear, Viewer/Editor visibility, hidden-parent 404/redaction, empty results and read-only Trash. Scanned light/dark pages had zero axe violations and no horizontal overflow; desktop/mobile screenshots were visually reviewed. The localhost HTTP session-bootstrap limitation above still applies: no trusted-HTTPS browser auth or multipart interoperability is claimed.

The focused backend selection passed **95 tests**, zero failures/errors/skips. The initial run hit the pre-existing historical default-schema V26 checksum drift; no repair or migration edit was made. The successful rerun used fresh default schema `frontend_m2_verification_20261024` with all three datasource/Flyway overrides; independently isolated suites used their UUID schemas. Live frontend tests used fresh schema `frontend_m2_live_20261024` in `vaultdrive_test`; all normal autonomous processors remained disabled. Full backend storage/broker regression and the existing trusted-HTTPS/CORS/clock release matrix were not run.

Backend reproduction from `backend/`, with the ignored private environment loaded in the same shell:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=AuthControllerTest,AuthenticationSessionIntegrationTest,ApiContractFreezeIntegrationTest,SearchIntegrationTest,TrashControllerTest,ApiContractConcurrencyIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m2_verification_20261024' '-Dspring.flyway.schemas=frontend_m2_verification_20261024' '-Dspring.flyway.default-schema=frontend_m2_verification_20261024' test
```

## Executed M1 verification — 2026-10-24 (historical)

Verified with repository-local Node **24.22.1** (the host's Node 20 is too old for the selected tooling). Clean `npm ci` succeeded; npm reported zero vulnerabilities and the dependency tree had no peer errors. API drift checking, TypeScript, ESLint, Prettier, **44 Vitest tests**, and the production build passed. Git attributes keep frontend text LF for reproducible checks after Windows checkouts.

**Nine Chrome scenarios passed** across desktop, emulated phone and tablet: native POST credential fallback, form validation, server redirects, themes, all shell routes, reload restoration, account dialogs, keyboard focus, no horizontal overflow, no-store headers and real BFF HTTP registration/login/profile/quota/logout. Axe reported zero violations in the scanned light/dark pages. The isolated Spring/PostgreSQL auth/contract selection passed **43 tests**, zero failures/errors/skips. Broader backend storage/broker regression was not rerun for this frontend-only change.

The live frontend calls used a fresh `frontend_m1_*` schema in the existing test database. Normal autonomous processors remained disabled; schema/test accounts are retained. These results preserve the HTTP bootstrap and trusted-HTTPS/browser-authentication limitations above. Browser multipart and sustained clock verification were not executed. No shared infrastructure was changed.

## Known limits

- Exactly one Node process; no serverless, horizontal scaling, clustered PM2 or rolling multi-process deployment. Shared durable credential storage and distributed serialization require a later reviewed implementation. Sticky routing alone does not provide failover-safe rotation.
- Authentication forms require JavaScript. Their native fallback submits with POST so credentials do not become URL query parameters when scripts are unavailable.
- Process restarts require login. Sessions are bounded in memory, without persistence or performance/capacity claims. Backend families remain PostgreSQL-authoritative and expire independently.
- No preview, file/folder mutations, sharing management, restore/permanent deletion, activity/notifications, profile editing or account recovery. No file content or counts are fabricated.
- The backend supports fixed order only; arbitrary date/type/size sorting is deferred. Offset pages are independent snapshots and can shift under concurrent changes. Explicit refresh resets accumulated pages and selection and rereads quota.
- No persistent resource cache, virtualization or performance/capacity claims. Loaded pages and selection are scoped to the current navigation; back/forward restores the location and reloads bounded feeds, not prior accumulated pages.
- Real-browser trusted HTTPS/CORS/clock/native-expiry/recovery/download verification remains [blocked/open](../docs/browser-multipart-verification-2026-10-23.md). Local HTTP UI checks do not close it. No multipart interoperability is claimed.
- CSP limits framing/base/form/object embedding; a nonce-based script policy and operational edge/fleet abuse controls remain deployment work. No deployment or infrastructure changes are included.

Stop at M3.
