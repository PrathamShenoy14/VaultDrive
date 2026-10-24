# Frontend M3 transfers

M4 now adds namespace/file management and copy/deletion operation UX; see
[the M4 contract](frontend-m4-management.md). The M3 boundary below is historical.

M3 adds file selection/drop, a workspace upload queue, direct multipart parts,
empty-file creation and authenticated native downloads. Backend contracts,
migrations, storage evidence, ownership, quota and lock order are unchanged.
Preview, namespace/file mutations, sharing management and notifications remain
outside this milestone. The [browser release gate](browser-multipart-verification-2026-10-23.md)
remains open.

## Architecture and bounds

`frontend/src/features/transfers/` contains OpenAPI-derived DTO aliases, a narrow
API adapter, a dependency-injectable upload manager, direct-storage transport and
React controls. One provider in the protected workspace layout survives client
navigation. Each file captures its destination when selected. Root My Drive and
fresh folders with `UPLOAD` expose the picker/drop zone; Shared entry points,
Viewer folders, search and Trash do not infer upload destinations. Backend checks
remain authoritative after capabilities or quota change.

The panel holds at most **50 transfers**, with **two active file/control tasks**
and **one part per file**. Each part is at most **8 MiB**, following the returned
fixed geometry, up to **640 parts / 5 GiB per file**. Hashing reads a sliced
ArrayBuffer, never the entire large file; Web Crypto and the browser may make
bounded copies. No measured memory, throughput or five-GiB capacity claim is made.
Signing requests contain one expectation. Resume listing accepts at most seven
100-part pages with increasing markers and ordered, unique part numbers.

Multipart bytes travel from Chrome directly to the exact HTTPS signed Garage
URL. No file bytes go through Next.js or Spring upload APIs. The browser sends
only `x-amz-checksum-sha256`, with no API bearer/cookie or script-set Host,
Content-Length or Content-Type. ArrayBuffer framing supplies the signed length.
The client validates the exact signed host/headers/length/hash/registration and
expiry (five-second local margin), without rewriting URLs or correcting clocks.
XHR provides progress and local abort, with a two-minute part timeout.

Opaque ETag quotes are retained. Every successful PUT requires exposed nonblank
ETag and matching SHA-256, followed by exact backend ListParts size/hash/ETag
comparison. Resume hashes local slices against authoritative existing parts and
retained in-memory ETags; only missing parts get refreshed capabilities and
identical-content PUTs. Changed immutable registration or evidence blocks completion.
The backend chooses its own completion manifest; the browser sends no manifest.

## States and recovery UX

| Frontend state | Meaning and allowed continuation |
|---|---|
| queued | Waiting for a bounded slot; Cancel can discard purely local work. |
| preparing | Admission or one initiation request is in progress. Cancellation waits for its response before inspecting the session. |
| uploading | Hash/verify/upload one part at a time. Bytes sent are provisional; 100% sent does not mean READY. |
| paused | File re-selection, interrupted part, URL refresh or explicit admission retry is needed. Resume lists backend parts before sending identical bytes. |
| pending | Completion/cancellation/empty publication is incomplete. Poll GET; recovery is an explicit user action. |
| unknown | Control dispatch or response is uncertain. Retain key/operation ID, check status and use documented recovery. Never blindly reinitiate, recomplete or reabort. |
| blocked | Authorization, quota/name/deadline/lifecycle, unsupported storage binding or evidence prevents progress. Inspect status and resolve eligibility; operator attention may be necessary. |
| failed | Invalid local input or authoritative FAILED/REMOVED empty creation; not a proof of physical cleanup. |
| cancelling | One cancellation request is pending. Local XHR abort alone never becomes backend CANCELLED. |
| cancelled | Local-only queue removal or backend cancellation evidence. No physical Garage reclamation is asserted. |
| completed | Backend completion disposition / empty state is READY. Download still rechecks current access. |

Pending/unknown operations poll **GET only**, initially after two seconds, doubling
to 30 seconds, at most ten checks per action. Polls share the two-task bound and
never dispatch recovery automatically. Check status restarts this bounded window;
there is no completion SLA. INITIATING can remain unknown indefinitely and has no
client reinitiation recovery. An uncertain completion without a visible decision
also stays retained instead of interpreting a stale OPEN snapshot as retry permission.

Explicit Recover invokes `/recover` only for retained completion/empty work or
`/cancel/recover` for abandonment. The backend decides whether positive evidence,
receipt-only finalization or a previously undispatched frozen decision may advance.
It never repeats an already uncertain storage effect. A received 429 means admission
before business work; Retry-After disables immediate explicit retry. No mutation
has an automatic retry loop. Only the rejected control request can roll back its
new dispatch marker; a later status/recovery/replay rate limit never erases an
earlier uncertain outcome or turns it into purely local cancellation. A 409
refreshes quota and explains that name, quota,
deadline or lifecycle can cause conflicts without parsing backend message text.
Permission/session failures remain visible; 401 offers sign-in.

Nonempty admission can be explicitly replayed with the original key after a lost
response. Zero-byte files use `/files/empty` with a retained key, never multipart.
UNKNOWN/STORAGE_COMPLETE use status and positive-only recovery; only a known
RESERVED intent may be explicitly replayed to dispatch its original empty write.
An empty request already sent has no cancellation endpoint.

Session-scoped browser storage retains only a user-ID-scoped journal of names,
size/type/modification time/destination, idempotency key, operation/session ID and
last control dispatch. No File bytes, signed URL, provider upload ID, JWT, refresh
token or CSRF secret is persisted. Reload requires reselecting the original file;
registered size/hash checks remain authoritative. Restore itself sends no mutation.
Tab closure, cleared/disabled browser storage and other devices have no automatic
discovery because the backend has no operation-list API. Visible IDs/keys should
be retained for operator investigation when storage is disabled. Browser storage
is a convenience, never authority. New uploads in another tab are separate queues;
the existing BFF still serializes refresh across tabs in its one Node process.

READY, admission and cancellation refresh server quota and the current explorer
feeds; changes to a formerly selected location appear on its next read. Downloads
are available in file row/grid action menus and the single-file selection toolbar.
Folders and unsupported bulk download actions are not fabricated. Controls use
native file inputs, labeled progress and Base UI menus, including existing keyboard
shortcuts. The panel can minimize on narrow screens and scroll independently.

## Downloads and security

`GET /api/v1/files/{uuid}/download` is allowlisted by the existing BFF. Native
same-origin links open an attachment, with errors in a separate tab so the workspace
survives. The BFF obtains its server-held bearer under the session lock and releases
that lock after upstream headers. It returns the upstream ReadableStream directly,
preserving MIME, Content-Disposition, Content-Length (including zero), nosniff and
no-store. It does not read a Blob/ArrayBuffer or buffer the response. The header
deadline is ten seconds; the stream has a 310-second outer bound around Spring's
five-minute async budget. Client disconnect/cancellation reaches the upstream
request/body. Metadata keeps its ten-second deadline; transfer controls have a
25-second BFF / 30-second client deadline and never retry uncertain requests.

Downloads accept only the opaque session, ignore browser-supplied bearer/CSRF,
reject foreign Origin and cross-site/same-site Fetch Metadata, and forward no
browser Cookie to ordinary backend APIs. GET does not require backend cookie-CSRF.
All transfer POSTs require the exact configured HTTPS Origin/custom header and
bounded documented JSON/no-body requests. Only admission forwards Idempotency-Key.
Unlisted mutations remain closed. The single-process session constraints in
[ADR-0017](decisions/0017-frontend-bff-sessions.md) remain unchanged.

Before streaming, download errors get a sanitized status-specific HTML page for
native navigation, including sign-in/workspace links; API callers retain JSON
envelopes/Retry-After. After headers commit, an interrupted stream cannot become
a JSON error. Chrome owns progress, cancellation and failure reporting. The UI
does not claim successful completion merely because a link was clicked. Retry
is explicit; no presigned GET, Range resume or automatic download retry exists.
Already admitted downloads may finish after revocation. A five-GiB declared
mock stream proves nonbuffering shape only; real fixture-size downloads and
capacity/load claims are separate evidence.

## Reproduction and remaining gates

Run the normal frontend drift/type/lint/format/test/build checks from
[the README](../frontend/README.md#verification). `npm run test:e2e -- m3.spec.ts`
uses real installed Chrome across desktop, phone and tablet projects. Start the
built frontend with its approved APP_ORIGIN and an isolated backend on loopback.
Use a fresh `vaultdrive_test` schema and keep all autonomous processors/probes off.
No production/shared user fixtures are permitted. The test creates synthetic
accounts/objects and retains those fixtures; traces and automatic failure
screenshots are disabled. Reviewed screenshots contain synthetic UI only.

The live M3 scenario uses the HTTP API client with the configured HTTPS Origin to
bootstrap the HttpOnly session and create real empty/multipart fixtures. Multipart
fixture PUTs use Node, **not Chrome**. Chrome downloads those actual Garage bytes,
compares size and whole-file SHA-256, and checks local HTTP upload admission fails
closed. This does not prove trusted-HTTPS auth or browser-to-Garage upload CORS.
The separate mocked Chrome scenario intercepts API/storage responses, exercises
real browser slicing/hash/XHR/progress/UI/recovery, and contributes **zero** Garage
interoperability passes. Vitest also mocks transport/storage; backend integration
tests are recorded separately. See the execution record in the README.

For actual browser multipart, obtain explicit operator approval for the concrete
TLS endpoints and merged exact-origin CORS policy in the
[existing continuation procedure](browser-multipart-verification-2026-10-23.md#configuration-plan-requiring-explicit-approval).
The page origin must equal APP_ORIGIN; Garage's immutable backend signing endpoint
must already be trusted HTTPS reachable unchanged by browser and JVM. Approve only
PUT/checksum and expose ETag/checksum for that page origin, preserving unrelated
bucket rules. Then run the real picker/drop for a single part, 8 MiB + 8 MiB + 37
bytes, simultaneous files and an empty file; verify actual network destinations,
forbidden-header framing and exact backend evidence. Continue the existing matrix:
identical retry, interruption/reselection/resume, native URL expiry, completion loss
and positive recovery, RESERVED/OPEN cancellation, quota exhaustion, Editor revoke,
downloads/empty downloads/interruption, native session expiry and sustained
browser/JVM/PostgreSQL/Garage clock bounds. Do not alter signed URLs, clocks, TTLs,
TLS validation or cookie flags to substitute for any gate.
