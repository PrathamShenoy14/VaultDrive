# Next.js backend integration guide

Contract freeze: **2026-10-23**. Use the [73-operation inventory](api-endpoint-inventory.md)
and [OpenAPI 3.1 JSON](openapi.json) for methods, parameters, request/response
schemas, security, success codes and error envelopes. The feature contracts linked
below supply the storage and authorization rules that a schema cannot express.
The [freeze record](api-contract-freeze.md) separates integration readiness from
the unresolved browser and deployment launch gates.
All abbreviated endpoint paths below are under `/api/v1`.

## Transport and authentication

Serve the Next.js page and `/api/v1/**` through **one trusted HTTPS origin**.
Proxy that path to Spring Boot, preserving method, query, body, Authorization,
Cookie, Set-Cookie, X-VaultDrive-CSRF, X-VaultDrive-Client, Idempotency-Key,
If-Match, ETag, Location, Retry-After, Date and download headers. Disable response
caching and automatic mutation retries in the proxy. Apply the same no-cache rule
to Next.js server fetches and personalized route caches. Keep credentials out of
logs, URLs, analytics and error reports. This remains the backend integration
contract. [Frontend M1](../frontend/README.md) implements a server-held BFF variant:
browser login/refresh return 204 without readable JWT/CSRF credentials, while the
Next.js server follows the backend contract below. See
[ADR-0017](decisions/0017-frontend-bff-sessions.md) for the explicit single-process
boundary, cookie handling, serialized rotation and fail-closed restart behavior.

[Frontend M2](../frontend/README.md) now implements read-only Drive/Shared/Trash
navigation and filename/filter search through additional allowlisted BFF GETs.
It forwards only documented per-route query names, preserves fixed backend
orders, requests independent 50-entry pages and never reconstructs private
ancestry. [Frontend M3](frontend-m3-transfers.md) adds bounded direct browser parts,
upload/empty recovery controls and native authenticated streaming downloads to the
same BFF. No multipart bytes traverse the BFF. Preview, namespace/file mutations
and sharing management remain closed. The backend freeze and release gates are unchanged.

Spring Security has **no cross-origin API CORS policy**. A browser at a different
scheme/host/port cannot use a direct credentialed API integration. Do not solve
this by weakening Secure/Strict cookies or reflecting origins. A same-origin proxy
is the supported MVP topology; a separately hosted API needs a reviewed contract
change. Test preflights cannot prove real browser cookie behavior.

1. Register with `POST /auth/register`: email, password (12..72 characters and at
   most 72 UTF-8 bytes), displayName (nonblank, at most 100). It returns 201 and
   `{userId,message}`, **without logging in**. Email is trimmed/lowercased;
   duplicate registration returns 409.
2. Login with `POST /auth/login`, JSON email/password and exactly
   `X-VaultDrive-Client: 1`. Read `{accessToken,tokenType,expiresIn}` and the
   `X-VaultDrive-CSRF` response header. Use same-origin credentials so the browser
   accepts the refresh cookie. The cookie is host-only
   `__Host-vaultdrive-refresh; Secure; HttpOnly; SameSite=Strict; Path=/`.
3. Send `Authorization: Bearer <accessToken>` on ordinary APIs. A cookie alone
   never authorizes them. Keep access tokens in memory; retain the CSRF secret
   across reloads in an appropriate same-origin client store. It is a credential.
4. Refresh with `POST /auth/refresh`, cookie and exactly one CSRF header, **omit
   Authorization**, no query string. Serialize rotation across all tabs/callers.
   One in-flight promise in a tab alone is insufficient across tabs. Read the new
   token/cookie; the CSRF value remains session bound. Access defaults to 30 minutes;
   refresh has a fixed seven-day absolute family expiry, never extended by rotation.
   Next.js middleware/server actions must not introduce independent refresh paths.
5. A lost/uncertain refresh response requires login again. Retrying a consumed
   refresh token revokes its whole family, including outstanding access JWTs.
   Missing/wrong CSRF is 403; invalid/expired/replayed refresh is 401. Login,
   refresh and logout reject query strings. Losing the CSRF value requires login;
   there is no CSRF bootstrap/recovery endpoint.
6. Logout with `POST /auth/logout`: valid bearer, matching refresh cookie and CSRF.
   Only committed 204 clears the cookie. Repeated logout with the revoked JWT is
   401. If access has expired, refresh once through the serialized path before
   logout, or reauthenticate after an uncertain rotation. There is no cookie-only
   or global logout endpoint.

GET `/users/me` returns `{userId,email,displayName,createdAt,updatedAt}`. PATCH
accepts only displayName, trims it, rejects blank/over-100/control characters and
returns the same DTO. Additional submitted identity/credential fields confer no
authority. There is no password reset, email change, account deletion or user
directory API. See [session security and failure semantics](authentication-sessions.md).

## Navigation and metadata

At root, fetch GET `/folders` and GET `/files`. For a folder UUID, fetch GET
`/folders/{id}`, GET `/folders/{id}/children` and GET `/files?folderId={id}`.
Folder and file feeds are separate, independently paginated; there is no combined
folder-contents page or global total. GET `/shared-with-me` supplies direct shared
entry points; GET `/search?q=...` searches owned/effectively shared resources.

Metadata supplies `effectiveRole` and `allowedCapabilities`. Render actions from
capabilities, then handle fresh 403/404/409 at mutation time. A null parent means
root **or private-parent redaction**. Build breadcrumbs only through accessible
parent DTOs and stop at null/404; do not infer a shared owner's root or private
ancestry. The frontend may retain its own navigation stack. No breadcrumb
endpoint is required for this bounded, redaction-preserving workflow.

Create folders with POST `/folders` `{name,parentFolderId}` (201 `{folderId}`),
then read metadata. PATCH `/folders/{id}` renames; PATCH `/files/{id}/name` renames
a file. File rename preserves its existing extension. Move is owner-only:
PATCH `/folders/{id}/move` `{destinationFolderId}` or PATCH `/files/{id}/move`
`{folderId}`. Omitted/null parent or destination means the caller's root where
the endpoint supports it. Names are trimmed, case-sensitive, at most 255, with
path separators/control characters rejected; collisions return 409. File and
folder namespaces are separate. See [capabilities and namespace behavior](api-contract.md).

Owner/Editor may create in an editable destination and rename an editable target.
Editor Trash additionally requires an editable **immediate non-null parent**;
direct Editor on a target under a private parent does not suffice. Viewer reads
and downloads only. Owner-only actions include move, copy, restore, permanent
delete and grant/link management. Visible insufficient privilege gives 403;
unknown/foreign/hidden resources give the same 404. Trash/purge ancestry and
hidden copy staging are barriers even for owners' ordinary resource reads.

## Pagination, sorting, errors and retry

All ordinary page feeds use `{content,page,size,totalElements,totalPages}`,
page default 0/nonnegative int32, size default 50/range 1..100, 64-bit offsets.
Out-of-range pages are empty and retain visible totals. There is no arbitrary
`sort` contract: do not submit sort and expect a chosen order. Part listing is
the separate marker protocol below. Grant lists (`{direct,inherited}`) and file
share-link lists (array) are deliberately unpaged management collections.

| Feed | Fixed order |
|---|---|
| Root/child folders | lower(name) C collation, name C collation, UUID ascending |
| Files / Shared with me | database name, UUID ascending |
| File/folder Trash | deletedAt descending, UUID ascending |
| Search | lower(name) C, name C, resourceType, UUID ascending |
| Resource/permission history | occurredAt descending, UUID descending |
| Notifications | createdAt descending, UUID descending |
| Share links | createdAt, UUID ascending |

Counts/page share a statement snapshot, but separate requests are not a frozen
snapshot. Refresh affected lists after mutations; concurrent inserts/permission
changes may shift later offset pages. Search filters combine with AND; MIME/size
filters with FOLDER are invalid. Use literal substring query, not SQL wildcards.
See [search bounds and date rules](api-contract.md#filename-search-and-filtering-2026-10-22).

JSON errors are `{status: number,error: UPPERCASE_HTTP_SYMBOL,message: string}`.
Branch on status/error and operation state, never parse message text. Bean field
messages are sorted and exclude rejected values; malformed JSON/types use
sanitized 400. Domain validation messages can be explanatory. The schema has no
field-error array or request-correlation field. 401 means authenticate; 403 means
insufficient permission or failed CSRF admission; 404 intentionally conflates
missing and inaccessible. 409 covers quota/name/lifecycle/idempotency conflict.
Grant PATCH uses 428 (missing If-Match), 412 (stale), 400 (malformed).
405/415/406 describe unsupported method/request media/response media.

Signed 64-bit fields are JSON numbers, not decimal strings. JavaScript clients
must preserve precision where quota/count/revision values may exceed
Number.MAX_SAFE_INTEGER, for example with lossless parsing at the transport
boundary. The bounded per-file size (5 GiB) fits an ordinary JavaScript number.
Do not silently round a version used for a grant precondition.

429 has no-store and Retry-After: 1 and occurs before business work, possibly
before authentication. Back off with jitter, preserving the original key/request.
GET is safe to retry. Database bounded timeouts use 503/Retry-After: 2; other
503 paths may omit the hint. Lost responses, 202 and 5xx **do not prove rollback**.
Read retained operation state and use its explicit recovery path. Do not blindly
repeat refresh or uncertain storage controls. Poll nonterminal jobs no faster
than two seconds, add backoff and bound UI polling; there is no completion SLA.
There is no universal Idempotency-Key support on ordinary metadata mutations.

## Nonempty multipart upload

In the numbered transfer steps, `/{id}/...` is relative to `/upload-sessions`.

Persist original normalized admission request, Idempotency-Key and returned
session/file UUIDs. Keys are 1..128 printable ASCII excluding spaces; same key
and normalized input replays the original reservation (201), changed input gives
409. Session keys are scoped to owner/initiator; they do not expire. A new key
means a new admission, not recovery.

1. POST `/upload-sessions` `{folderId,name,size,contentType}`. Size is positive,
   at most 5 GiB; null/omitted MIME defaults octet-stream. Use returned geometry:
   8 MiB parts, up to 640, shorter final part; immutable 24-hour session expiry.
   Owner or destination Editor may reserve; quota belongs to destination owner.
2. POST `/{id}/initiate` once. 200/OPEN contains durable upload identity; 202 with
   INITIATING means pending/unknown. Read GET `/{id}`. There is no unknown-Create
   repair API and no authority to redispatch it. Expiry does not prove no bytes.
3. Hash exact part bytes with SHA-256, canonical Base64 of 32 bytes. POST
   `/{id}/part-urls` `{parts:[{partNumber,length,sha256}]}`, batches 1..100.
   Responses are an array of `{part:{partNumber,length,sha256,registrationId},
   upload:{url,requiredHeaders,expiresAt}}`. Refresh with identical expectations;
   changing committed length/digest gives 409.
4. PUT exact bytes to the exact signed Garage URL, credentials omitted, no API
   Authorization. Send the checksum header; the browser generates matching Host
   and Content-Length. Never rewrite URL authority/path/query or set forbidden
   browser headers. Same-content part retry is supported within capability
   validity; different-content replacement is outside the protocol.
5. GET `/{id}/parts?marker=0&limit=100` and follow `nextMarker` until null.
   Verify authoritative number/size/digest/opaque ETag against registered bytes.
   On reload/interruption reselect the same file and resume missing parts with
   identical expectations. Browser PUT evidence alone is not completion evidence.
6. POST `/{id}/complete` with no manifest/body. The backend lists parts, freezes
   the manifest, permits one Complete, records positive evidence, then publishes
   READY. GET session and inspect **completion.disposition**, not session.state.
   On pending/uncertain result use POST `/{id}/recover`, never another storage
   completion request. Recovery cannot redispatch a committed Complete.
7. To abandon, POST `/{id}/cancel` once; GET `/{id}/cancellation` polls it;
   POST `/{id}/cancel/recover` can advance an undispatched cancellation. Completion
   and abandonment are mutually exclusive. Existing dispatches are never retried.
   Logical expiry is an opt-in backend processor, not a frontend endpoint.

Existing session operations allow the owner or original initiator with current
Editor authority at the original destination; other Editors/revoked callers get
404. Owner recovery is possible where lifecycle permits. Previously signed URLs
cannot be recalled by revocation. Session responses expose protocol upload and
dispatch UUIDs/IDs; they are not credentials or permission to issue storage
control calls. There is no upload-session list endpoint: retain session IDs and
keys in client transfer state. Historical inaccessible sessions can return 404.

See [sessions](multipart-upload-sessions.md), [parts](multipart-part-transfers.md),
[completion](multipart-completion-recovery.md), [cancellation](multipart-cancellation-expiry.md)
and [delegated authority](sharing-m3.md).

## Empty files, copies, Trash and recovery states

For size zero use POST `/files/empty` with Idempotency-Key and
`{folderId,name,contentType}`. It creates a real object; multipart rejects zero.
200 means READY; other create snapshots use 202, including FAILED/REMOVED replay.
GET returned Location or POST its `/recover` (always 200 snapshots). Recovery
never repeats PUT. There is no empty-creation cancellation endpoint.

File copy: POST `/files/{sourceId}/copy` with retained Idempotency-Key and
`{destinationFolderId,name}`. Poll `/copy-operations/{id}`; explicit `/execute`
advances undispatched RESERVED work, `/recover` probes positive evidence/finalizes,
`/cancel` abandons unfinished publication. Folder copy uses POST
`/folders/{sourceId}/copy`, `/folder-copy-operations/{id}` and the same suffixes
plus `/discard`. It snapshots at most 1,000 members/depth 64, advances at most
four steps per request, and hides the whole destination until publication.
Cancel retains partial charges; discard separately accepts purge and returns
`disposalOperationId`. No copy worker automatically advances either workflow.
Both require owned source/destination, exact name, no overwrite; no grants/links
are copied. See [file copy](file-copy.md) and [folder copy](folder-copy.md).

DELETE `/files/{id}` or `/folders/{id}` trashes with 204. Owners read separate
GET `/files/trash` and `/trash/folders` common-entry pages. Use RESTORE/PURGE
capabilities; pending entries have none. POST `/files/{id}/restore` returns file
metadata; POST `/trash/folders/{id}/restore` returns
`{folderId,restoredName,parentFolderId}`. Reflect the returned fallback/name.
DELETE `/files/{id}/permanent` or `/trash/folders/{id}/permanent` returns
202 `{operationId}`, Location and Retry-After: 2. Retain that ID: repeating the
permanent-delete request is 404, not an idempotent acceptance. GET the deletion
operation as its original owner, including after metadata removal.

| Workflow / authoritative field | Exact states and frontend action |
|---|---|
| Deletion `state` | ACCEPTED: intent saved; PROCESSING: processing evidence, no SLA; BLOCKED: show reasonCode/operator attention; FAILED: show failure/operator recovery; LOGICALLY_COMPLETED: logical deletion finished |
| Session `state` | RESERVED: not initiated; INITIATING: initiation pending/unknown, never redispatch Create; OPEN: parts allowed; COMPLETION_DECIDED: inspect completion; ABANDONMENT_DECIDED: inspect cancellation |
| Session `completion.disposition` | PENDING: frozen decision; UNKNOWN: dispatch uncertain; STORAGE_COMPLETE: positive receipt; BLOCKED: reason and lifecycle recovery; READY: usable publication completed |
| Cancellation `disposition` | PENDING: intent; UNKNOWN: abort outcome uncertain; BLOCKED: reason; CANCELLED: never-initiated proof or retained abort acknowledgement |
| Empty creation `state` | RESERVED: no dispatch, same admission replay may dispatch; UNKNOWN: PUT uncertain, recover only; STORAGE_COMPLETE: receipt but publication blocked; READY: published; FAILED: failed metadata; REMOVED: metadata absent |
| File copy `state` | RESERVED: explicit execute; UNKNOWN: recover only; STORAGE_COMPLETE/BLOCKED: receipt/eligibility recovery; READY: publication succeeded; FAILED/CANCELLED: terminal unpublished outcome |
| Folder copy `state` | ACTIVE: explicit bounded execute; BLOCKED: correct eligibility/quota then execute/recover as applicable; UNKNOWN: recover existing member evidence; COMPLETED: whole-tree publication; FAILED/CANCELLED: terminal, optionally discard |

HTTP 200 status reads can describe incomplete/failed operations; 202 is never a
success-completion signal. Neither file metadata `UPLOADING/READY/FAILED` nor
session state substitutes for operation state. Unknown may remain indefinitely;
absence/timeouts/expiry are not failure proof. New unfamiliar state/reason should
render a conservative status and refresh, never enable a new dispatch. A deletion
FAILED can later progress through audited operator recovery or gain completion
evidence. Completed copy/empty operation history grants no current file access:
read ordinary metadata again. Cancellation, REMOVED, LOGICALLY_COMPLETED, logical
quota release and abort acknowledgement never assert physical reclamation.

## Downloads, sharing, notifications and quota

GET `/files/{id}/download` uses bearer authentication and returns attachment bytes,
declared/fallback MIME, Content-Length (including 0), no-store and nosniff. A plain
anchor cannot supply the bearer header; the frontend must preserve authenticated
transport in its download flow. Handle streams without requiring a 5-GiB in-memory
blob. There is no presigned GET or supported Range/resume-download contract.
After headers commit, stream failure can truncate bytes instead of returning JSON;
check length/integrity where the client flow supports it and allow explicit retry.
An already admitted download may finish after revocation.

Owners POST `/files/{id}/grants` or `/folders/{id}/grants` with
`{email,role:VIEWER|EDITOR}` for an existing
registered recipient. GET returns direct/inherited lists; update at the grant's
source with PATCH and quoted If-Match version, consume returned ETag. Revoke with
DELETE. No recipient directory/autocomplete endpoint exists. Shared reads use
strongest current direct/inherited role. A stale notification or role display
does not confer authority.

File-only share links: POST `/files/{id}/share-links` `{expiresAt:null|instant}`,
read the returned **secret once**. GET lists links without secrets; DELETE revokes.
Default expiry 24 hours, explicit future expiry at most seven days. Public GET
`/public/share-links/{linkId}` and `/public/share-links/{linkId}/download` require exactly one
`X-VaultDrive-Share-Token`; never put the secret in a query string. A future
frontend may use a fragment then remove it before issuing header-based requests;
there is no backend share-page URL. Public metadata is `{name,contentType,sizeBytes}`;
public downloads always octet-stream attachments. Invalid/revoked/expired/hidden
links, malformed IDs and unsupported public methods/routes all return uniform
404; 429 can precede validation. See [link security](sharing-m4.md).

GET `/notifications` supports unread/eventType/page/size; GET `/notifications/unread-count`
returns `{unreadCount}`; PATCH `/notifications/{id}/read` is idempotent 204. Recipient isolation
applies before count/page. `actionable:false` means no resource navigation;
obsolete grant events display generic history, while quota events can display
their safe numeric snapshots. Nullable resource fields must be handled. Grant events
can arrive late/reordered. Quota events contain nullable `quota` event-time
threshold/usage/limit snapshots, never action authority. There is no push/SSE or
delivery SLA; use bounded polling. GET `/activity` and `/permission-activity`
are separate owner-only histories, including after resource removal.

GET `/users/me/storage` returns logical limit/used/reserved/available plus separate
uncertain allocation estimates. Do not add uncertainStorageBytes to used/reserved;
they can overlap. Refresh usage after uploads/copies/removal, and on 409 quota
conflict. There is no quota-edit endpoint. Zero-limit or exactly-full accounts can
create empty files; already-over-limit accounts cannot. Physical capacity and
retention remain operational launch gates.

## Garage CORS and the release gate

The same-origin API proxy does **not** solve direct Garage PUT CORS. Garage needs
the exact approved page origin, PUT, checksum request header and exposed ETag/
checksum response headers. Browser storage requests omit credentials; no
Authorization/cookie, wildcard credential policy or browser Create/Complete/Abort
is needed. Use the [existing template/runbook](browser-multipart-interoperability.md).

The [2026-10-23 real-browser verification](browser-multipart-verification-2026-10-23.md)
remains **BLOCKED/NOT EXECUTED**. Trusted HTTPS page/storage origins, approved
working origin policy, browser cookie/preflight/framing/evidence/native expiry,
recovery/download/revocation scenarios and sustained clock bounds remain open.
Repository tests and this freeze do not close that gate. No VM, Garage, DNS,
certificate, clock or shared infrastructure configuration is changed here.
