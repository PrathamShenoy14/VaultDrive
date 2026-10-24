# VaultDrive frontend M1

Next.js App Router foundation, real backend authentication and a responsive Drive-inspired shell. The frozen [OpenAPI contract](../docs/openapi.json) and backend remain authoritative. No backend runtime, migration or infrastructure changes are included.

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
src/features/shell/            Navigation, account menu, storage and placeholders
src/lib/api/                   Generated types and typed lossless JSON transport
src/lib/env.ts                 Server environment validation
scripts/generate-api.mjs       Reproducible contract generation/drift check
tests/                        Isolated transport/session/UI tests
tests/e2e/                    Chrome UI + real backend HTTP integration checks
```

- `/` redirects to `/drive`.
- `/login`, `/register` provide real backend-connected forms. Registration requires a subsequent login.
- `/drive`, `/shared`, `/activity`, `/trash` have server-side authentication and deliberate feature placeholders. No file, search, sharing, Trash or notification data is requested.
- `/session/restore` posts to the sole BFF refresh coordinator, then reloads `/drive`. It never receives tokens.
- `/api/v1/[...path]` allows only POST `auth/register|login|refresh|logout` and GET `users/me|users/me/storage`. Other operations return 404; unsupported methods return 405. Query strings are rejected in this M1 subset.

Server Components own protected profile/quota reads; Client Components handle forms, theme, dialogs, menus and responsive navigation. There is no TanStack Query dependency because M1 needs no client-side resource feed/cache. React Hook Form uses Zod validation and field errors without an additional resolver adapter. Tailwind semantic tokens support light/dark themes and future file grids/tables. Base UI provides focus management and keyboard behavior. `next-themes` stores only a theme preference in localStorage.

## Authentication and proxy design

See [ADR-0017](../docs/decisions/0017-frontend-bff-sessions.md). The browser gets an opaque random 256-bit `__Host-vaultdrive-session` cookie: Secure, HttpOnly, SameSite=Strict, Path=/, no Domain. Access JWTs, the session CSRF secret and the current backend cookie jar remain in server-only process memory. They are never serialized in HTML/RSC, JSON, URLs, analytics, browser storage or application logs. The backend's Secure/HttpOnly refresh cookie is also forwarded unchanged to the browser, but the BFF always uses its authoritative server-held jar, ignoring stale browser refresh cookies and supplied Authorization/CSRF headers.

Login sends exactly `X-VaultDrive-Client: 1` to Spring. Backend login/refresh returns a JWT and CSRF header; the BFF consumes them and returns **204**, deliberately differing from the backend's 200 token response. Registration and profile/quota JSON remain backend responses. The browser helper's types model this separate BFF boundary explicitly; generated types describe the backend.

Every BFF mutation requires the exact configured Origin and `X-VaultDrive-Client: 1`, rejects cross-site/same-site Fetch Metadata, and requires JSON for login/registration. Backend cookie mutations use exactly its stored CSRF header. Refresh omits Authorization and queries; logout includes the latest bearer, refresh cookie and CSRF. Cookie headers use `Headers.getSetCookie()` and individual append operations, including multiple cookies with comma-containing Expires attributes. No comma splitting or attribute weakening occurs. Ordinary backend requests are bearer-only. Responses/fetches are no-store, personalized pages are dynamic, login/logout use full navigations to discard the Router Cache, and the proxy does not expose later-milestone endpoints.

The backend's `expiresIn` controls access expiry (normally 1800 seconds). A 15-second early-refresh margin reduces clock-boundary failures; it does not establish monitored clock correctness. The browser's opaque session has at most seven days, and server expiry can only decrease with the backend's remaining cookie lifetime. Rotation never extends the family. An async per-session lock covers refresh, protected reads and logout across all tabs/requests in the one supported Node process. Waiting callers reread the session and use its latest credentials; duplicate refresh POSTs do not rotate an already-fresh token. Logout is serialized with them. No mutation is automatically retried. A backend 401 for an unexpired bearer destroys local state rather than trying to refresh a revoked family.

RSC reads authenticate against `/users/me` on the server; each page and each data read has its own guard rather than relying solely on a layout. Expired access redirects to the restore page because RSC rendering cannot write cookies. No middleware, Server Action, render or prefetch starts a separate rotation. Missing/expired/revoked/logged-out sessions lead to login. Restore lands at `/drive`, without arbitrary return URLs.

Any lost/incomplete/failed refresh response abandons local credentials and requires login; it is never retried. A received 429 retains the session because backend admission precedes business work, preserves Retry-After and requires an explicit later attempt. Other 5xx rotations are treated as uncertain. Only committed backend 204 logout clears the backend refresh cookie; failed logout preserves the backend response. A server restart loses local sessions and fails closed. Replacing a login or losing local state does not assert backend-wide revocation; orphaned backend families retain their fixed expiry. This is not global logout.

Backend error envelopes `{status,error,message}` and Retry-After pass through unchanged. The typed client retains both and presents status-based messages for 400/401/403/404/409/429/5xx without parsing messages. Network failures become sanitized 502; failed protected reads render an error/retry boundary, and quota failure has an explicit retry state. No automatic mutation retry/backoff loop exists.

## Generated types and integer precision

```powershell
npm run api:generate
npm run api:check
```

`openapi-typescript` reads `../docs/openapi.json`. Never edit `src/lib/api/generated.ts` manually. The deterministic transform maps int64 to `number | bigint`; `lossless-json` preserves unsafe integers as bigint at the transport boundary. The quota reader normalizes every quota counter to bigint, and storage formatting/percentage arithmetic stays exact before its bounded presentation conversion. Large values are never silently rounded. TypeScript 5 and ESLint 9 are pinned to the newest mutually supported peer lines of openapi-typescript and Next.js's bundled lint plugins; incompatible newer majors are deliberately excluded. The lockfile pins the resolved stable package set.

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

These suites create isolated test schemas. No backend production code changed, so storage/purge/broker regression is outside this frontend slice.

## Executed verification — 2026-10-24

Verified with repository-local Node **24.22.1** (the host's Node 20 is too old for the selected tooling). Clean `npm ci` succeeded; npm reported zero vulnerabilities and the dependency tree had no peer errors. API drift checking, TypeScript, ESLint, Prettier, **44 Vitest tests**, and the production build passed. Git attributes keep frontend text LF for reproducible checks after Windows checkouts.

**Nine Chrome scenarios passed** across desktop, emulated phone and tablet: native POST credential fallback, form validation, server redirects, themes, all shell routes, reload restoration, account dialogs, keyboard focus, no horizontal overflow, no-store headers and real BFF HTTP registration/login/profile/quota/logout. Axe reported zero violations in the scanned light/dark pages. The isolated Spring/PostgreSQL auth/contract selection passed **43 tests**, zero failures/errors/skips. Broader backend storage/broker regression was not rerun for this frontend-only change.

The live frontend calls used a fresh `frontend_m1_*` schema in the existing test database. Normal autonomous processors remained disabled; schema/test accounts are retained. These results preserve the HTTP bootstrap and trusted-HTTPS/browser-authentication limitations above. Browser multipart and sustained clock verification were not executed. No shared infrastructure was changed.

## Known limits

- Exactly one Node process; no serverless, horizontal scaling, clustered PM2 or rolling multi-process deployment. Shared durable credential storage and distributed serialization require a later reviewed implementation. Sticky routing alone does not provide failover-safe rotation.
- Authentication forms require JavaScript. Their native fallback submits with POST so credentials do not become URL query parameters when scripts are unavailable.
- Process restarts require login. Sessions are bounded in memory, without persistence or performance/capacity claims. Backend families remain PostgreSQL-authoritative and expire independently.
- No file browsing, uploads/multipart, file actions, sharing, search, notifications, profile editing or account recovery. Existing-file counts and notifications are never fabricated.
- Real-browser trusted HTTPS/CORS/clock/native-expiry/recovery/download verification remains [blocked/open](../docs/browser-multipart-verification-2026-10-23.md). Local HTTP UI checks do not close it. No multipart interoperability is claimed.
- CSP limits framing/base/form/object embedding; a nonce-based script policy and operational edge/fleet abuse controls remain deployment work. No deployment or infrastructure changes are included.

Stop at M1.
