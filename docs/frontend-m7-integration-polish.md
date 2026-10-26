# Frontend M7: integration review and polish

Baseline: M6 `36f33f9`; initially clean working tree. This is a stabilization
review, with no backend contract, infrastructure or session-architecture expansion.

## Review checklist and acceptance criteria

- Functional: registration/login, restoration/refresh/logout and cross-tab changes;
  Drive/nested breadcrumbs, Shared/Search/Trash, filters/paging/selection;
  folder/file management, operation journals and explicit recovery;
  uploads/downloads, direct/inherited grants and public links;
  notifications, histories, profile and exact quota semantics.
- Visual and responsive: desktop/tablet/phone, light/dark, all major screens,
  dialogs/menus/panels, hierarchy/spacing, overflow, scrolling and touch targets.
- Accessibility: axe plus keyboard landmarks, labels, focus visibility,
  Tab/Shift+Tab trapping, Escape/return focus, errors/progress announcements,
  contrast and enlarged text. Automated scans are partial evidence only.
- Security: exact HTTPS Origin/custom-header admission, HttpOnly credentials,
  server-held JWT/CSRF, serialized refresh, secret/ticket handling, redaction and
  authorization-aware capabilities; preserve no-store and cancellation.
- Reliability/failures: offline/503, 401/403/404/409/429, quota, revoked access,
  interrupted transfers, unknown mutation results, delayed/disabled workers;
  never infer rollback, physical reclamation or success from acceptance alone.
- Performance: observe duplicate requests/races, bounded polling and queues,
  scope/unmount/logout cleanup; change only demonstrated problems and make no
  unmeasured performance claim.
- Verification: generated API and backend contract drift, typecheck, lint with
  zero warnings, formatting, complete frontend tests/build, relevant backend
  regressions and representative browser checks. Classify every browser result
  as real HTTPS integration, mocked UI, live Garage or unverified multipart.
- Delivery: ranked findings with reproduction/evidence, fixes and limitations,
  exact commands/counts, README/learning updates, reviewed explicit staging and
  one local commit; no push or production/infrastructure remediation.

## Assessment and prioritized findings

M7 preserves the frozen API, existing design system and single-process BFF.
The authorized stabilization work is complete; this is not production readiness.
No backend production code, migration, dependency, generated API type,
certificate, worker configuration or shared infrastructure is changed.
Production readiness remains gated below. Severity describes user impact;
it is not a claim that an exploit or data loss occurred.

| Severity         | Reproduction / affected surface                                                                                                                                                                                    | Implemented correction and evidence                                                                                                                                                                                                                                                                                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1               | Start an explorer, transfer, management or sharing request, then pause/logout. M6 hid the workspace but those earlier clients did not share its abort lifetime. A queued BFF request could later refresh/dispatch. | Shared authenticated request lifetime, 401 invalidation across tabs, cancellation checks before dispatch and after reading a response; BFF fences all protected lock waiters before/after serialized refresh. `session-lifetime.test.ts` and `session-bff.test.ts` reproduce cancellation, late success, refused status and in-flight rotation. |
| P2               | Stop the workspace while transfer completion-recovery reads status; the response could enable a subsequent recovery mutation. Management controls also lacked stopped-store guards.                                | Fence recovery after the awaited read and every management entry point. Pause stops providers, queues, timers and active part requests; resume restores journals without mutation. Original keys/IDs and uncertainty remain.                                                                                                                    |
| P2               | Load folder page 0, fail a refresh with 503, then Try again. The retry inferred page 1 from the previous success.                                                                                                  | Retain failed page/replacement mode and retry that exact read. Regression observes `0,0,0`, previously `0,0,1`; stale rows are labelled and fresh 401/403/404 removes rows/selection.                                                                                                                                                           |
| P2               | At 320px wide the shell search input had **0px** usable text width after its icon/clear padding.                                                                                                                   | Full-width search row below account controls on phones; browser asserts more than 80px usable text width. Desktop/tablet retain the existing layout.                                                                                                                                                                                            |
| P2               | Open account details with a 100-character name at 320×360 and 200% root text size: dialog top was **−136px**, long nested grid content overflowed, and default first-link focus scrolled its title away.           | Viewport-bound scrolling, minimum-zero grid tracks, wrapping sort controls, initial title focus and explicit return focus. Browser checks title visibility, bounds, Tab/Shift+Tab, Escape and restored account focus.                                                                                                                           |
| P2               | Queue three mocked UNKNOWN empty transfers at 320×320: panel top was **−34px**, clipping its heading/Minimize control.                                                                                             | Bound the outer panel to the dynamic viewport with scrolling; bounds, expansion/minimization and session pause/resume are verified.                                                                                                                                                                                                             |
| P2               | Sign out from the account dialog while logout is pending. The dialog portal is outside the hidden workspace subtree.                                                                                               | Close account details before pausing; a refused logout restores the dialog and actionable error. A delayed-response UI regression covers both states.                                                                                                                                                                                           |
| P3               | Short desktop sidebar/mobile navigation could exceed available height; explorer checkboxes were 16px.                                                                                                              | Scrollable navigation and 24px native checkbox targets. Existing 28px small buttons exceed the 24px minimum; main shell controls are 44px.                                                                                                                                                                                                      |
| Test maintenance | M2 expected the disabled pre-M4 `Restore · M4` placeholder; M6 could click a streamed replacement-account header before menu hydration. A backend expiry test used JVM time for a PostgreSQL rule.                 | Assert actual Restore capability, wait for hard-reload settlement, and anchor the backend test fixture to `statement_timestamp()`. No runtime expiry rule is bypassed.                                                                                                                                                                          |

The baseline had 388 passing unit tests and 39/45 passing browser cases, with
three stale M2 assertions and three multipart-signing failures. New focused
reproductions failed before their fixes. An early M7 upload test acted before
the picker/provider had hydrated; it now waits for the actual enabled upload
control. The first final browser pass had a tablet replacement-menu timing
failure, reproduced by its retained context; the corrected focused recheck passed.
An additional share-link selection initially had 29 passes and the JVM-clock
fixture failure. The corrected complete relevant backend selection passed below.

## Functional and contract review

| Journey               | Evidence and limits                                                                                                                                                                                                                                                                                                                                                                             |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Authentication        | Actual HTTPS form registration/login in M4–M7, separate registration/login semantics, cookie restoration, profile/quota reads, logout and cross-tab replacement/invalidation. Protected redirects, refresh serialization, uncertain refresh, absolute expiry and cancellation are covered by BFF/backend regressions. No real 30-minute idle-expiry wait or multi-process guarantee is claimed. |
| Explorer              | Real root/nested folders, accessible breadcrumbs, separate feeds, paging, URL filters/history/reload, list/grid, Shared, Trash and current metadata. Unit tests cover selection ranges, keyboard and search cancellation. Mocked Chrome covers offline/503/403/404/409/429 and exact explicit recovery.                                                                                         |
| Management            | Real create/rename/move/Trash/restore, independent file/folder copies and accepted permanent deletion. Mocked lost admissions/UNKNOWN states verify retained IDs/keys, bounded continuation and explicit recovery. Logical completion remains distinct from physical cleanup.                                                                                                                   |
| Transfers             | Real browser file-picker zero-byte admission reaches READY, then native download has the expected filename and empty-file SHA-256. Intercepted Chrome tests exercise nonempty slicing/hash/XHR, queueing, cancellation, recovery and navigation; unit tests cover limits, retries, re-selection, quota/conflicts and journals. No real direct multipart success is claimed.                     |
| Sharing               | Real registered-email Viewer/Editor grants, direct/inherited access, versioned changes, revocation, private-parent redaction, one-time Viewer links, anonymous access/download, database-clock expiry and revoked denial. Missing-user/conflict/stale-version/lost-secret responses are mocked; backend tests exercise actual authorization and concurrency.                                    |
| Notifications/account | Actual profile editing, exact bigint quota, owner resource/permission histories, filters/pages and disabled-worker empty inbox. Delivered notification snapshots, acknowledgment/paging/redaction and client 401 are intercepted scenarios. No live RabbitMQ delivery or global logout claim.                                                                                                   |

The review used the frontend README/M1–M6 records, integration guide, frozen
inventory/OpenAPI, ADR-0017 and the actual frontend policies/transports/providers
and existing backend regression patterns. No unsupported endpoint was added.
Ordinary mutations are dispatched once; 429 does not erase earlier uncertain
work. Copy controls retain original requests/keys and explicit continuation.
No UI change converts 202, UNKNOWN, expired/cancelled state or worker-disabled
delay into success or proof of rollback/reclamation.

## Visual, accessibility and browser evidence

Chrome **156.0.8078.47**: desktop **1280×720**, emulated iPhone **390×664**,
emulated iPad **768×1024**. Additional stress checks use 320×568, 320×360 with
200% root text and 320×320. These are emulated viewports, not physical-device,
Safari or Firefox verification. HTTPS browser runs use Next's development server
and the existing trusted certificate; the optimized production build is checked
separately. No TLS bypass is used.

Screenshots and DOM geometry were inspected for shell/auth, explorer list/grid,
management dialogs, sharing/access/link dialogs, the public page, account,
notifications/activity, failure rows and transfer panels. Light/dark theme tokens,
typography, spacing and hierarchy are preserved. No replacement component system
or speculative visual redesign is introduced.

Local, ignored evidence is retained under `frontend/test-results/`:

- `m7-before/**/m7-narrow-search.png`, `m7-account-enlarged.png` and
  `m7-transfers-short.png`: before-fix reproduction screenshots.
- `m7-final/**/m7-narrow-search.png`, `m7-account-enlarged.png`,
  `m7-transfers-short.png`, `m7-settings-dark.png`, `m7-rate-limit.png`: scoped
  before/final comparisons; the later title-focus check has separate output.
- `m7-complete/`: final full-suite screenshots including M4 confirmations,
  M5 grants/public pages and M6 notification/history/account evidence.
- `m7-title-fixed/`: title visibility and short enlarged-text navigation check.
- `m7-focused-final/`: all 12 final M7 viewport cases, including enlarged-text
  title/Close separation and the explorer keyboard/failure walkthrough.

Axe scans cover the major pages, permission/management/account dialogs, native
download failure/public-link pages and intercepted failure states. Passing scans
have zero violations and no document horizontal overflow. Keyboard walkthroughs
use real Chrome key events: navigation/bell opening, modal Tab/Shift+Tab trapping,
Escape and return focus, native form labels/validation and context-menu access.
The final M7 explorer walkthrough also checks Skip to content, Space, Arrow keys,
Shift range selection, Home/End, Ctrl+A, Escape and Shift+F10. Dialog titles reserve
space for Close; the enlarged-text check measures that their text does not overlap.
These checks and screenshot review supplement axe; they are not a complete
screen-reader audit or WCAG certification. Root font enlargement is not a claim
of OS/browser-zoom, high-contrast mode or assistive-device coverage.

## Security, reliability and performance

The BFF still requires exact configured HTTPS Origin/custom-header/Fetch Metadata
admission and narrowly allowlists methods/queries. Browser Authorization and CSRF
credentials are not forwarded. JWT/CSRF/current backend cookies stay server-held;
the opaque browser session remains Secure/HttpOnly/Strict/host-only and no-store.
One session lock owns refresh; cancellation after a completed rotation preserves
new credentials/cookies and never retries the consumed token. Already dispatched
mutations can commit after the browser aborts; journals keep recovery evidence.

Public-link fragments are removed before reads, tokens travel only in their
dedicated header, owner lists never reconstruct secrets, and the process-local
download handoff stays bounded at 256 one-use tickets with 60-second expiry.
Existing isolated ticket tests cover expiry/replay; actual public reads/downloads,
expiry/revocation and metadata redaction are exercised in Chrome/backend tests.
Creation secrets are masked in intentional screenshots. Traces remain disabled.

Permission changes recheck current backend capabilities; 403 is not an automatic
refresh or a sign-in loop. M7 adds no unsafe retries, cache of resource authority,
secret storage, insecure HTTP fallback or backend contract expansion.

Reliability improvements are cancellation fencing and correct failed-read retries;
no latency, throughput, memory or capacity improvement is claimed. Existing
limits remain two file tasks, 50 transfers, sliced 8 MiB parts, at most ten status
polls with backoff, and five unread-count checks at 60-second intervals. Search
reads abort on scope changes and logout clears local file handles/timers/queues.
Development StrictMode can produce cancelled duplicate reads; it is not evidence
of production request amplification. Explorer loaded pages and retained management
journals can accumulate within a long-lived scope/tab; no large-list, long-session
heap/load or virtualization benchmark was performed. A future retention policy
must preserve unresolved operation evidence.

## Infrastructure diagnostics and remaining launch gates

1. **Clock agreement remains blocked.** Read-only host/SSH/PostgreSQL checks at
   host `2026-10-26T15:03:59Z` / VM `2026-10-24T22:06:59Z` showed approximately
   **40h 57m** drift, with the VM behind. Later PostgreSQL corroborated October 24.
   The backend HTTP Date matches Windows/JVM. A disposable admission at host
   `2026-10-26T15:21:16Z` returned database creation
   `2026-10-24T22:25:42Z` and expiry `2026-10-25T22:25:42Z`.
   Initiation returned 200; part signing returned sanitized 400
   `Invalid multipart request`. Signing caps its deadline to the session expiry,
   already past for the JVM. Signature validity, browser deadline checks, link
   display/expiry and time-sensitive recovery can disagree. Neither clock nor
   expiry enforcement was changed. The backend test now tests database admission
   against database time; this does not close cross-system clock bounds.
2. **Direct browser-to-Garage multipart remains unverified/blocked.** Current
   storage is HTTP on localhost:3900; a trusted HTTPS HEAD probe fails
   `ERR_SSL_WRONG_VERSION_NUMBER`. OPTIONS against a nonexistent path under
   `vaultdrive-files`, with page Origin `https://localhost:3000`, PUT and the
   checksum request header, returns 403. The root OPTIONS returning 200 with
   `Allow-Methods: GET` and wildcard origin is insufficient; it supplies neither
   required PUT/checksum admission nor exposed ETag/checksum proof. No presigned
   URL, secret or credential is logged. No real browser multipart integrity run
   is possible on this binding. The three existing M3 multipart-dependent native
   download fixture cases now explicitly skip if the admitted deadline is past
   according to backend Date. Nonempty browser native-download verification is
   therefore also not re-established by this M7 run. Existing historical M3
   evidence and backend Garage checks are separate.
3. **BFF remains single-process.** Sessions and refresh locks are in memory;
   restart loses sessions and requires login. Multiple replicas, cluster/serverless
   scheduling and rolling failover are unsupported without shared durable
   credentials and distributed rotation serialization. Public download handoffs
   are also process-local. Sticky routing is insufficient for safe failover.
4. **Retention/capacity remain unresolved.** Trash/logical deletion, accepted
   asynchronous cleanup, logical quota release and positive physical reclamation
   are distinct. Retained or uncertain objects can consume physical capacity
   outside logical quota. Test fixtures/schemas and the diagnostic session are
   retained; no sweeping/adoption/abort of ambiguous allocations was attempted.
5. **Normal workers remain disabled.** Outbox publisher, notification/purge
   consumers, job/reconciliation/expiry/finalization processors and probes were
   not enabled. Delayed deletion and an empty inbox are expected in that mode.
   Isolated existing backend tests exercise their own fixture logic; this is not
   a continuous deployment-worker or RabbitMQ delivery verification.
6. Deployment still needs the existing edge/logging/CSP/abuse controls,
   restore-tested backups, measured physical capacity and retention/erasure policy.
   No production deployment, infrastructure remediation or distributed session
   redesign belongs to this milestone.

Before launch, obtain explicit approval for clock/storage HTTPS/CORS remediation,
then run the actual trusted browser multipart/integrity/expiry/interruption/
recovery/revocation matrix. Separately validate the approved worker lifecycle,
physical retention/capacity and supported session topology. Broaden keyboard,
screen-reader, physical touch-device and production-runtime checks.

## Verification commands and results

Use repository-local Node **24.22.1**. From `frontend/`:

```powershell
$env:Path = 'C:\Users\ABC\Desktop\VaultDrive\.tools\node_modules\node\bin;' + $env:Path
npm run typecheck
npm run lint -- --max-warnings=0
npm run format:check
npm run api:check
npm test
npm run build
$env:NODE_USE_SYSTEM_CA = '1'
$env:E2E_BASE_URL = 'https://localhost:3000'
$env:E2E_APP_ORIGIN = 'https://localhost:3000'
npm run test:e2e -- --output=test-results/m7-complete
```

Use already-running servers with the private backend environment, test database
and isolated M7 schema `frontend_m7_live_20261026`, reused between runs with fresh
UUID accounts and retained fixture rows. Startup follows the
existing M4 HTTPS certificate command in the README and sets all normal worker
flags false. Do not read/commit private keys or bypass system trust. Disposable
UUID accounts/fixtures are retained; do not point these tests at shared user data.

Backend from `backend/`, with the environment loaded in the same shell:

```powershell
. .\.env.local.ps1
.\mvnw.cmd -o '-Dtest=AuthControllerTest,AuthenticationSessionIntegrationTest,ApiContractFreezeIntegrationTest,ApiContractConcurrencyIntegrationTest,SearchIntegrationTest,TrashControllerTest,SharingIntegrationTest,SharingConcurrencyIntegrationTest,ShareLink*Test,SharingDownloadIntegrationTest,MultipartSessionIntegrationTest,DelegatedMultipartIntegrationTest,FileCopyIntegrationTest,FolderCopyIntegrationTest,StorageUsageIntegrationTest,ResourceActivityIntegrationTest,PermissionActivityIntegrationTest,SharingNotificationIntegrationTest,QuotaNotificationIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=frontend_m7_final_20261026' '-Dspring.flyway.schemas=frontend_m7_final_20261026' '-Dspring.flyway.default-schema=frontend_m7_final_20261026' test
```

The combined backend selection passed **458 tests, zero failures/errors/skips**
at `2026-10-26T15:31:58Z`. It combines PostgreSQL/authorization/concurrency and
real Garage public-download tests with isolated controller/admission/token tests;
it is not 458 live end-to-end operations. The full unrelated broker/purge/process
suite was not rerun. Independent UUID-schema suites retain their existing
isolation. API export comparison and generated frontend drift do not update the
frozen artifacts.

Final frontend checks passed: strict typecheck, ESLint with zero warnings,
Prettier, generated API drift, **416 tests in 21 files** (28 more than M6),
and the optimized production build. The final focused M7 Chrome run passed
**12/12 scenarios** across all three viewports. The final complete M1–M7 run
passed **54 cases, zero failures, with 3 clock-gated skips** in 9.6 minutes.

| Browser evidence class                                        |     Cases | Meaning                                                                                                                                                                                    |
| ------------------------------------------------------------- | --------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Actual HTTPS/frontend/BFF/backend journeys                    | 24 passed | 18 unintercepted form-based M4–M7 journeys and 6 legacy M1/M2 API-client session bootstraps; real explorer, management, sharing, public access, histories/account and zero-byte operations |
| Public frontend validation/layout/native fallback             |  6 passed | M1 controls, themes, protected redirects and no-JavaScript POST behavior; not authenticated backend journey proof                                                                          |
| Mixed real authentication with intercepted workflow responses | 24 passed | Multipart transport/UNKNOWN recovery, management failures, sharing loss/conflicts, notification delivery/redaction/401, short transfers and explorer failure/keyboard checks               |
| Clock-dependent nonempty Garage download fixture              | 3 skipped | M3 part signing cannot proceed with a deadline already past the JVM; no nonempty browser download or direct multipart pass is inferred                                                     |

Actual Garage-backed browser evidence is zero-byte upload/native authenticated
download and public-link downloads of real fixtures. Separate backend tests also
stream nonempty Garage bytes. Neither evidence class proves direct browser
multipart transfer/integrity. The complete suite includes the axe, keyboard and
light/dark/responsive checks described above. These passes do not close launch gates.

Logs are ignored under `frontend/m7-*.log` and
`backend/target/m7-*.log`; screenshots are ignored under `frontend/test-results`.
Early sandbox `spawn EPERM` prevented the first Vitest startup; the supported
runtime/local worker execution then ran successfully. This was not a test pass.
One concurrent unit invocation timed out at the existing five-second bound;
the final sequential full run passed without increasing timeouts or changing
worker configuration. Intermediate browser runs exposed title scroll/focus and
walkthrough-fixture timing/range-selection issues, corrected and rechecked before
the final source was frozen for the complete run.

## Changed-file inventory

- Session admission/lifetime: `frontend/src/features/auth/session-request.ts`,
  `auth/server/bff.ts`, `experience/api.ts`, `explorer/api.ts`,
  `management/model.ts`, `management/provider.tsx`, `sharing/api.ts`,
  `transfers/api.ts`, `transfers/manager.ts`, `transfers/provider.tsx`.
- UI: `frontend/src/components/ui/dialog.tsx`, `features/explorer/explorer.tsx`,
  `resource-feed.tsx`, `search-box.tsx`, `features/shell/account-menu.tsx`,
  `app-shell.tsx`, `features/transfers/transfer-panel.tsx`.
- Verification: `frontend/tests/session-lifetime.test.ts`, `session-bff.test.ts`,
  `explorer.test.tsx`, `management.test.ts`, `transfers.test.ts`, `ui.test.tsx`,
  `setup.ts`, `e2e/m2.spec.ts`, `e2e/m3.spec.ts`, `e2e/m6.spec.ts`, `e2e/m7.spec.ts`;
  `backend/src/test/java/com/vaultdrive/sharing/ShareLinkIntegrationTest.java`.
- Documentation: this record, `frontend/README.md`, `docs/architecture.md`,
  `docs/frontend-integration.md`, `docs/learnings.md`.

The existing generated-file and certificate exclusions are preserved;
`next-env.d.ts`, generated API types and frozen contract files have no intended
diff. Only these 34 task paths belong to the local M7 commit. No push or production
deployment. Stop after M7.
