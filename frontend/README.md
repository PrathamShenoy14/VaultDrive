# VaultDrive frontend M1 and M2

Next.js App Router, real backend authentication and a responsive Drive-inspired file explorer. M2 adds read-only navigation, selection, metadata, search, Shared and Trash. The frozen [OpenAPI contract](../docs/openapi.json) and backend remain authoritative. No backend runtime, migration or infrastructure changes are included.

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
src/features/explorer/         Read-only feeds, selection, metadata, breadcrumbs and search
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
- `/api/v1/[...path]` allows POST `auth/register|login|refresh|logout` and GET `users/me|users/me/storage`, `folders`, `folders/{uuid}`, `folders/{uuid}/children`, `files`, `files/{uuid}`, `shared-with-me`, `search`, `files/trash`, `trash/folders`. Only documented query names are forwarded for each read. Duplicate, unknown and oversized queries are rejected; domain validation and authorization remain in Spring. Unlisted paths return 404 and resource mutations return 405.

Server Components own protected profile/quota reads; Client Components handle forms, theme, dialogs, menus and responsive navigation. M2 keeps scope-local, abortable read state rather than introducing TanStack Query/Table: independent feeds, explicit page loading and native checkbox/keyboard interactions do not yet need a shared resource cache or table framework. React Hook Form uses Zod validation and field errors without an additional resolver adapter. Tailwind semantic tokens support light/dark themes and future file grids/tables. Base UI provides focus management and keyboard behavior. `next-themes` stores only a theme preference in localStorage.

## Explorer behavior and contracts

Folder/file feeds request **50 entries each**, with independent totals and explicit Load more controls. No global combined total is invented. The displayed order is the backend's fixed name order (Trash: deletion date descending); sorting controls deliberately expose no unsupported options and never submit `sort`. New pages are deduplicated by resource type plus UUID because concurrent offset snapshots can shift. Refresh restarts the scope and rereads server quota; M2 performs no quota-changing operations.

Click a checkbox or row to select; Ctrl/Command click toggles and Shift click selects a range within a feed. Ctrl/Command+A within a resource group selects its loaded items; Escape clears selection. Arrow Up/Down, Home/End move row focus, Space toggles, Enter opens folders or metadata, and Shift+F10/right click opens the same Base UI action menu as the visible actions button. Folder names also provide an explicit open control for touch/keyboard users. List/grid switching preserves selection. Details use a fresh metadata GET and Base UI's focus-managed sheet; unavailable/revoked metadata renders an error rather than a stale snapshot.

Search debounces at 400 ms and supports `resourceType`, `scope`, `mimeType`, `minSizeBytes`, `maxSizeBytes`, `createdFrom`, `createdTo`, `modifiedFrom`, `modifiedTo`. Filters combine with AND; MIME/size with FOLDER is rejected by the backend. Inclusive dates use ISO 8601 instants with timezone. Clear search returns to My Drive; Reset filters keeps the query. Searches have independent bounded incremental pages, empty states, explicit retry and timeout handling. Scope changes abort old reads and discard their results.

Shared reads display actual Viewer/Editor roles and use `LIST_CHILDREN`/`READ_METADATA` snapshots for available read actions. Backend authorization remains authoritative. Breadcrumb traversal reads only returned parent IDs, stops at null or inaccessible ancestors, and is cycle/depth bounded. Shared is an entry-point link, never an inferred owner's root. Search folders open Shared or My Drive according to current metadata/capabilities; file results open metadata, not an inferred private location. Trash displays only its common-entry DTO, without active metadata calls or private-parent inference. Restore/permanent deletion are visibly deferred to M4; other future actions are disabled and restricted by returned capabilities.

Loading skeletons, per-feed empty/error states, no-store transport, explicit retries and authorization failures are covered by mocked tests. The client read deadline is 15 seconds, the server backend deadline 10 seconds. No automatic retry loop or cross-session cache is introduced. Server-held credentials and the existing single-process refresh coordinator are unchanged.

## Authentication and proxy design

See [ADR-0017](../docs/decisions/0017-frontend-bff-sessions.md). The browser gets an opaque random 256-bit `__Host-vaultdrive-session` cookie: Secure, HttpOnly, SameSite=Strict, Path=/, no Domain. Access JWTs, the session CSRF secret and the current backend cookie jar remain in server-only process memory. They are never serialized in HTML/RSC, JSON, URLs, analytics, browser storage or application logs. The backend's Secure/HttpOnly refresh cookie is also forwarded unchanged to the browser, but the BFF always uses its authoritative server-held jar, ignoring stale browser refresh cookies and supplied Authorization/CSRF headers.

Login sends exactly `X-VaultDrive-Client: 1` to Spring. Backend login/refresh returns a JWT and CSRF header; the BFF consumes them and returns **204**, deliberately differing from the backend's 200 token response. Registration and profile/quota JSON remain backend responses. The browser helper's types model this separate BFF boundary explicitly; generated types describe the backend.

Every BFF mutation requires the exact configured Origin and `X-VaultDrive-Client: 1`, rejects cross-site/same-site Fetch Metadata, and requires JSON for login/registration. Backend cookie mutations use exactly its stored CSRF header. Refresh omits Authorization and queries; logout includes the latest bearer, refresh cookie and CSRF. Cookie headers use `Headers.getSetCookie()` and individual append operations, including multiple cookies with comma-containing Expires attributes. No comma splitting or attribute weakening occurs. Ordinary backend requests are bearer-only. Responses/fetches are no-store, personalized pages are dynamic, login/logout use full navigations to discard the Router Cache, and the proxy exposes only the M1 authentication/profile/quota and M2 explorer reads. Downloads, previews, mutations and sharing management remain closed.

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
- No uploads/multipart, downloads, preview, file/folder mutations, sharing management, restore/permanent deletion, activity/notifications, profile editing or account recovery. No file content or counts are fabricated.
- The backend supports fixed order only; arbitrary date/type/size sorting is deferred. Offset pages are independent snapshots and can shift under concurrent changes. Explicit refresh resets accumulated pages and selection and rereads quota.
- No persistent resource cache, virtualization or performance/capacity claims. Loaded pages and selection are scoped to the current navigation; back/forward restores the location and reloads bounded feeds, not prior accumulated pages.
- Real-browser trusted HTTPS/CORS/clock/native-expiry/recovery/download verification remains [blocked/open](../docs/browser-multipart-verification-2026-10-23.md). Local HTTP UI checks do not close it. No multipart interoperability is claimed.
- CSP limits framing/base/form/object embedding; a nonce-based script policy and operational edge/fleet abuse controls remain deployment work. No deployment or infrastructure changes are included.

Stop at M2.
