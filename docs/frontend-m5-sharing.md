# Frontend M5: sharing and collaboration

M5 adds sharing to the existing explorer and BFF without changing the frozen
backend, migrations, dependencies or infrastructure. Activity/notifications (M6),
preview and integration polish (M7) remain outside this milestone.

## API-to-UI mapping

All backend paths below are under `/api/v1`. DTOs come from generated OpenAPI
types. The backend remains the authority for every request.

| UI interaction               | Existing operation and authority                                                                                                  |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Fresh effective access       | GET `/files/{id}` or `/folders/{id}`; current metadata/capabilities, with private-parent redaction                                |
| Direct/inherited grant lists | GET `/files/{id}/grants` or `/folders/{id}/grants`; owner only; unpaged `{direct,inherited}`                                      |
| Grant existing recipient     | POST to either grant collection with `{email,role}`; role is VIEWER or EDITOR; owner only, exact registered-email lookup          |
| Change direct role           | PATCH the source grant collection's `/{grantId}` with `{role}` and quoted `If-Match`; owner only; returned version/ETag forwarded |
| Revoke direct access         | DELETE the source grant collection's `/{grantId}`; owner only; 204, absent matching grant is a no-op                              |
| File Viewer links            | GET/POST `/files/{id}/share-links`; owner only; POST `{expiresAt}` (null or instant) returns `{link,secret}` once                 |
| Revoke Viewer link           | DELETE `/files/{id}/share-links/{linkId}`; owner only; irreversible, matching repeat is a no-op                                   |
| Anonymous link metadata      | GET `/public/share-links/{linkId}` with exactly one `X-VaultDrive-Share-Token`; returns only name/contentType/sizeBytes           |
| Link download                | GET `/public/share-links/{linkId}/download` with that header; native attachment stream through the BFF                            |

There is no recipient directory, autocomplete, email invitation, folder link,
Editor link, renewal/update-link operation or backend share-page URL. Recipients
cannot fetch grant lists. The frontend therefore displays their effective role
and an explanation that sources/other recipients are private; it does not infer
whether their individual permission is direct or inherited.

## Explorer and access management

The Share action uses existing resource menus, keyboard/context menus,
single-selection actions and folder headers. It is available on active resources
with `MANAGE_GRANTS`. Readable collaborators get Access details instead. Trash
retains its existing capability/lifecycle interface; owner inspection/reduction
of grants on trashed resources is supported by the backend but is not exposed by
this active-resource dialog.

Opening the dialog reads fresh metadata before fetching owner-only collections.
Viewer reads/downloads. Editor additionally has backend-approved rename and
content creation in editable destinations. Editor Trash still requires an
editable immediate non-null parent. Move/copy/restore/permanent delete and sharing
management remain owner-only. The UI does not derive child capabilities from a
folder role: each DTO supplies its own `allowedCapabilities`.

Owners see recipient email/ID, direct role, timestamps, and the strongest
Viewer/Editor role across the direct/inherited rows for each recipient. Inherited
rows show a source-folder link and no mutation controls. Changing the source
grant affects descendants. Revoking a descendant direct grant may leave inherited
access intact; revoking an ancestor may leave an independent direct grant intact.
Confirmation text explains these cases. Owners keep their implicit Owner access.

Grant entry accepts an existing registered email and Viewer or Editor. Native
email validation catches ordinary mistakes; missing accounts/self-grants/role and
authorization decisions remain backend checks. Forms guard in-flight dispatches
with a synchronous lock. Every role change, direct revoke and link revoke requires
explicit confirmation. Base UI handles focus trapping/Escape/focus restoration;
semantic labels, native selects and responsive wrapping support keyboard/touch
interaction and light/dark themes.

Confirmed mutations reload sharing details and invalidate explorer feeds and
folder capabilities through `vaultdrive:sharing-changed` plus server refresh.
Previously loaded pages, URL state and eligible selected identities are retained
by the existing feed reconciliation. Navigation rereads its scope. Window focus
and returning visibility also revalidate explorer/dialog access. An open metadata
sheet is keyed to the explorer revision. Fresh denial clears recipient details,
secrets and privileged controls; inaccessible folders replace their contents
with the existing error UI. No real-time push or continuous polling is promised.

## Preconditions, errors and uncertainty

Grant versions use lossless JSON and exact quoted int64 `If-Match` values.
The BFF rejects missing/malformed/out-of-range preconditions; the backend detects
stale versions (412). A stale update requires refreshed access and a new explicit
role choice, never an overwrite or automatic retry. The BFF forwards ETag.

Controlled messages cover 400 (registered recipient/role/expiry), 401 (session),
403 (owner required), 404 (unavailable resource/grant), 409 (duplicate/lifecycle),
412/428 (version) and 429 (wait). Messages do not parse backend prose as error
codes, distinguish hidden foreign resources, or echo provider text.

Network errors/timeouts/server failures leave mutation outcomes uncertain and
block further writes until access is reread. There is no automatic mutation retry.
A lost link-create response may have created an active capability whose secret
cannot be recovered. Owners must inspect the link list, revoke unneeded entries,
and explicitly create a replacement. No permission journal or secret is persisted.

## Viewer link lifecycle and bearer security

Links grant file-only Viewer access independently from direct user grants.
Expiry defaults to server time plus 24 hours; an optional local-time input is
converted to an instant, at most seven days ahead. Server time and current
ancestry/lifecycle determine validity. Lists show creation/expiry/revocation and
status at the last check. Expired/revoked secrets are never reconstructed.

After creation, the dialog offers one URL and Copy link. It uses
`https://<current-origin>/share/{linkId}#<secret>`. The secret stays in component
memory until hidden/closed; list responses never contain it. Copy failure offers
manual selection. Copying deliberately transfers a bearer capability to the
clipboard; share it only with intended readers. Forgotten secrets require a new
link. Link revocation does not remove user grants, and vice versa.

The public `/share/{id}` page is outside the authenticated workspace. It captures
and replaces the fragment before issuing header-only metadata reads, omits browser
credentials, and never forwards the secret to login, a query, a request URL,
analytics, application logs or browser storage. The URL fragment is not an HTTP
request component. Reload requires the original capability link. Malformed,
expired, revoked and lifecycle-hidden capabilities display the same unavailable
message. Public metadata has no owner, resource/parent, recipient or storage key.

Native downloads use an exact-origin JSON POST to `/share/{id}/download` with the
secret in a bounded body and the client admission header. It prepares a random,
one-use ticket in a Secure/HttpOnly/Strict host cookie; native GET navigation
consumes it and forwards the existing public GET with the dedicated secret header.
The adapter streams without a client Blob or server buffering. It adds no backend
endpoint, principal, JWT or auth-session dependency. The map retains at most 256
pending handoffs for 60 seconds in this one Node process; timers release expired
secrets, successful consumption clears the cookie, and restart loses tickets.
The latest preparation replaces that browser's pending handoff; concurrent tabs
may need an explicit retry. Missing/expired/replayed/mismatched tickets fail closed.

This two-step transport preserves no-referrer and exact Origin together: Chrome
sets native form POST Origin to null under no-referrer, so accepting that form
would weaken admission. The ordinary fetch has the exact Origin and client header;
native GET needs only its opaque HttpOnly ticket. Ambient session cookies are never
forwarded to the public backend. Downloads reauthorize; admitted streams may finish
after expiry/revocation. Range/resume is unsupported. Check the browser download
panel for completion/interruption.

The public BFF accepts only the two public GET patterns, canonical 32-byte
base64url secrets and no queries. It forwards no ambient credentials or Set-Cookie,
uses manual redirects and controlled errors, and preserves no-store/no-referrer/
nosniff. All authenticated sharing writes use the established BFF: exact Origin,
client admission header, Fetch Metadata, bounded JSON/no-body checks and server-held
JWTs. Stored refresh CSRF remains confined to serialized rotation/logout; browser
Authorization/CSRF inputs confer no authority. Session cookies remain Secure,
HttpOnly and SameSite=Strict. Existing development certificate keys are ignored
and never staged. Proxy/APM operators must exclude capability headers and bodies
from external logging; no infrastructure/logging policy is provisioned here.

## Verification

Final commands, counts, integrated versus mocked evidence and remaining gates
are recorded in the frontend README. Tests cover direct create/update/revoke,
int64 preconditions, inherited/strongest-role display, collaborator redaction,
ancestor revocation, stale access, selection reconciliation, link lifecycle,
one-time secrets, fragment removal, header isolation, native download streaming and bounded one-use ticket expiry/replay,
session/Origin/CSRF admission, keyboard focus and accessible dialogs.

Use the existing trusted local HTTPS certificates and system CA trust; do not
disable TLS validation. Live browser scenarios use an isolated test schema with
all normal autonomous processors disabled. Disposable UUID accounts, metadata and
zero-byte Garage fixtures remain retained. Mocked failure scenarios demonstrate
UI state only. Backend regression tests separately exercise real PostgreSQL
constraints/ancestry/concurrency and Garage public downloads.

Real browser-to-Garage multipart remains unverified. The existing trusted storage
HTTPS/CORS/clock/native-expiry/fault/revocation matrix is still open. Local HTTPS
auth, public downloads and zero-byte fixtures do not close it. No VM, PostgreSQL,
Garage, RabbitMQ, certificate, clock or infrastructure setting is changed. The
single-process BFF boundary and deployment logging responsibilities remain.

Stop after frontend M5.
