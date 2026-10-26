# Frontend M6: notifications, activity and account experience

M6 extends the M5 baseline (`94dcdf6`) with notification history, owner activity,
profile editing and quota details. It uses the existing Next.js App Router,
generated types, lossless JSON transport and single-process BFF. No backend,
migration, dependency, infrastructure or frozen API change is included. M7 remains
outside this milestone.

## Backend contract mapping

All paths below are under `/api/v1`. Backend recipient/owner predicates apply
before filtering, counting and pagination. UI filters never confer authority.

| Experience | Existing contract | Frontend behavior |
| --- | --- | --- |
| Bell and unread count | GET `/notifications/unread-count` | Exact int64 count, visually capped at 99+; unknown/error is distinct from zero |
| Notification history | GET `/notifications?unread&eventType&page&size` | Bell panel: 10 entries and View all; dedicated `/notifications`: independent 20-entry pages, read-state/type filters |
| Acknowledge notification | PATCH `/notifications/{id}/read`, no body, idempotent 204 | One explicit request, then reread history and count; no optimistic decrement |
| Resource activity | GET `/activity?resourceType&resourceId&eventType&actorId&from&to&page&size` | `/activity`, 20-entry pages; resource-type/action controls; documented identity/time filters may be supplied in the URL |
| Permission activity | GET `/permission-activity?resourceType&resourceId&eventType&page&size` | Separate history selector; separate backend order and totals, never merged into a fabricated global timeline |
| Current access | GET `/files/{id}` or `/folders/{id}` | Explicit Open resource rechecks current capabilities; folders open existing Drive/Shared URLs, files use existing fresh metadata details |
| Profile | GET/PATCH `/users/me` | `/settings` reads registered identity/timestamps and edits only `{displayName}`; refreshes the shell after confirmed save |
| Quota | GET `/users/me/storage` | Existing shell meter plus detailed used/reserved/available and uncertain-allocation information in Settings |
| Session/logout | Existing POST `/auth/logout`, serialized refresh and server-held credentials | Existing account menu, cancellation and same-origin cross-tab session notifications |

There is no mark-all-read, mark-unread, notification-delete, push/SSE/WebSocket,
delivery SLA, global merged history, arbitrary sorting or client audit trail.
There is no editable email/password, password reset, account deletion, quota edit,
user directory or global logout operation. No control implies one exists.

## Notification and activity semantics

Notification types are GRANT_CREATED, GRANT_ROLE_CHANGED, GRANT_REVOKED and
STORAGE_QUOTA_THRESHOLD_CROSSED. Backend `actionable:false` suppresses resource
name, ID, role and navigation even if an inconsistent payload still contains
them. Obsolete/reordered/revoked grant notifications render generic history.
Quota alerts show only safe event-time numeric snapshots and their timestamp;
they link to current Settings, not to a resource.

Opening an actionable resource performs a current metadata check. A missing,
trashed, purged or revoked target produces a generic unavailable state; the
notification row drops stale resource details. No parent lookup or owner's root
is inferred for files. Notification content is rendered as React text, with no
HTML interpretation or backend-supplied URL navigation.

Resource history describes all eight contract events: upload completed, copied,
folder created, renamed, moved, trashed, restored and permanently deleted.
Permission history describes the three grant events and link created/revoked.
Owner-authorized actor/resource/recipient snapshots are displayed. Raw parent,
owner, actor, grant/subject and source-event IDs are not rendered. Rename uses
only this resource's before/after names. Legacy upload-initiator and deletion-
requester attribution is labelled explicitly. Removed resource history remains
readable to its owner, without pretending current resource access exists.

Both histories are owner-only, including history of actions performed by Editors;
being an actor or recipient is not permission to read another owner's history.
The UI never reconstructs events for reads/downloads, failed operations, purged
ancestors or unsupported transitions. This is supported history, not a complete
audit log. Feeds use the backend's fixed descending timestamp/UUID order. Offset
pages are independent snapshots and may shift under concurrent activity. Filters,
page and history selection live in the URL. All M6 timestamps explicitly use UTC.

Each read has loading, empty, error and explicit retry states, a 15-second client
deadline, no-store/same-origin transport and an abort signal. Unmount/navigation
discards late responses. Fresh read errors clear displayed history. There is no
automatic mutation retry. An uncertain profile save requires a refresh before
another submission; read-state uncertainty asks the user to check current state.

## Bounded updates and disabled local workers

The shell checks only unread count: immediately, then no faster than once per
60 seconds, at most five attempts per mounted cycle. There is one outstanding
request. Hiding the document cancels it; returning waits a full interval, with no
catch-up burst. Errors stop the cycle, including 429/503; no automatic rate-limit
retry occurs. Cleanup removes timers/listeners and cancels work. Explicit refresh
or a confirmed read mutation starts a new cycle. The panel shows when automatic
checks have paused. Full histories load on navigation, panel opening or explicit
refresh; they are not continuously polled.

Local notification consumers and the outbox publisher remain disabled. An empty
inbox can be expected even after a real sharing/quota event; it does not by itself
prove a frontend defect. Resource/permission history reads PostgreSQL snapshots
and remains useful without notification delivery. This implementation does not
enable or reconfigure RabbitMQ consumers, workers or publisher. Delivery recovery
and broker administration remain operator concerns.

## Account and quota semantics

The profile shows email, account ID, registered time and last profile update.
Display name is trimmed, required, limited to 100 UTF-16 characters and rejects
control characters, consistent with backend validation. The backend is authoritative.
Email and credential fields have no editable controls. Logout remains available
from the shell's account menu; its dialog links to full profile/storage Settings.

Quota arithmetic stays in bigint. Charge is `usedBytes + reservedBytes`; the meter
is visually clamped without rounding admission values. Warnings use the existing
80%, 90%, 100%, over-limit and zero-limit policies. Exactly-full/zero-limit accounts
may still create empty files; being over limit differs. Available bytes come
from the backend. Uncertain bytes/allocation counts are separate estimates and
can overlap reservations; they are never added to charge.

READY file lengths remain used, including Trash; UPLOADING lengths remain reserved.
Trash/restore do not release charge. Eligible metadata removal can release logical
quota without proving physical byte reclamation. Existing transfer/management
events and `router.refresh()` continue refreshing shell quota after uploads,
copies, cancellation, deletion, restoration and quota conflicts. Settings also
coalesces these events with a 500-ms delay, and offers explicit account/storage
refresh for external changes and worker progress. There is no physical-capacity
or erasure claim.

## Session, security and accessibility

The BFF narrowly allowlists these methods/query names. Duplicate/unknown queries,
unsupported routes, notification bodies and non-JSON profile mutations are rejected.
New PATCH operations use the existing exact configured HTTPS Origin, custom
`X-VaultDrive-Client: 1` and Fetch Metadata admission. Backend bearer and CSRF
credentials remain server-held; browser cookies remain HttpOnly/Secure/Strict.
Browser-supplied Authorization/CSRF values are not forwarded. Backend cookie CSRF
is still used only by the existing serialized refresh/logout paths. All responses
are no-store; ownership/recipient/lifecycle decisions remain backend checks.

Logout immediately aborts M6 requests and hides personalized content while its
outcome is pending. A refused logout restores the UI and existing error handling.
A successful logout or M6 401 broadcasts only a credential-free `ended` message;
other tabs discard personalized Router Cache through hard login navigation. A
login in another tab broadcasts `changed`, causing a hard reload under the current
shared browser cookie. A 403 is a permission failure, not an automatic refresh.
Cancelled M6 requests waiting for the BFF session lock cannot initiate a refresh
or backend dispatch. Already-started refresh keeps the existing uncertainty rules.
BroadcastChannel absence degrades to authoritative checks on subsequent requests.
The single-process session/restart limitations are unchanged.

Base UI manages panel/menu focus, Escape and return focus. Controls use native
labelled selects/buttons, UTC time elements, labelled meters and status/error
announcements. Navigation includes Activity, Notifications and Settings. Existing
theme tokens and wrapping layouts support desktop/tablet/mobile without replacing
the explorer, sharing or transfer UI.

## Verification

Use repository-local Node 24.22.1 on this machine. From `frontend/`:

```powershell
npm run api:check
npm run typecheck
npm run lint -- --max-warnings=0
npm run format:check
npm test
npm run build
$env:NODE_USE_SYSTEM_CA = '1'
$env:E2E_BASE_URL = 'https://localhost:3000'
$env:E2E_APP_ORIGIN = 'https://localhost:3000'
npm run test:e2e -- m6.spec.ts
```

Use the existing trusted certificate files; do not bypass TLS checks or regenerate
certificates. Run against a fresh isolated backend schema with all normal
processors off. Test accounts/schemas are retained. The browser fixture HTTP
client uses installed system CA trust; browser login uses actual HTTPS forms.

Backend from `backend/`, loading the ignored environment in the same shell:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=SharingNotificationIntegrationTest,QuotaNotificationIntegrationTest,PermissionActivityIntegrationTest,PermissionActivityConcurrencyIntegrationTest,ResourceActivityIntegrationTest,StorageUsageIntegrationTest,AuthControllerTest,AuthenticationSessionIntegrationTest,ApiContractFreezeIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m6_verification_20261026' '-Dspring.flyway.schemas=frontend_m6_verification_20261026' '-Dspring.flyway.default-schema=frontend_m6_verification_20261026' test
```

That selection passed **143 tests, zero failures/errors/skips** on 2026-10-26.
Independently isolated suites use their own UUID schemas. It includes actual
PostgreSQL recipient isolation, redaction, history/quota/session races and frozen
API comparison; adapter/controller tests also contribute to the count. No normal
consumer was enabled and no live RabbitMQ delivery is inferred.

Final frontend/browser results are recorded in the frontend README. Browser tests
explicitly separate actual HTTPS/backend account/history/empty-inbox/logout from
intercepted notification delivery/pagination/redaction and simulated 401 expiry.
No direct Chrome-to-Garage multipart verification is claimed. The existing
[multipart HTTPS/CORS/clock gate](browser-multipart-verification-2026-10-23.md),
physical capacity/retention and multi-process deployment gates remain open.

The M6 recheck observed a material existing clock mismatch: Windows reported
2026-10-26 while Ubuntu/PostgreSQL reported 2026-10-24. No clock was changed. The
broader M5 short-expiry test initially used a Windows-time deadline and therefore
did not expire during its wait. Its disposable fixture now anchors the requested
expiry to a fresh backend link's returned `createdAt`, still testing real backend
expiry. This test adjustment does not establish host/database/storage clock
agreement or close the clock gate. M6 displays backend instants in UTC without
substituting client timestamps.

The ordinary M5 Viewer-link fixture now supplies a supported explicit future
expiry. Under the observed drift, a backend-default 24-hour link can look expired
to the client and its one-time secret is conservatively cleared during access
revalidation. The fixture change preserves real access/download/revocation checks
without weakening that behavior. Clock agreement is still required for launch.

`next-env.d.ts` is tracked Next.js type glue and already matched its committed
route-type imports at the M6 baseline; no M6 hand edit or generated diff is needed.
Certificate files/private keys, local environments, logs and browser artifacts
are excluded from the commit. Stop after M6.
