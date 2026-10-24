# Frontend M4: file and folder management

M4 implements management through the existing Next.js BFF and frozen backend
contracts. No backend runtime, migration, storage or infrastructure configuration
changes are included. Sharing management (M5), activity/notifications (M6), preview
and integration polish (M7) remain outside this milestone.

## Interactions and capabilities

Create folder is available in owned root My Drive and returned CREATE_FOLDER
folders, including editable Shared folders. Rename, Move, Copy, Trash, Restore
and Delete permanently use one action definition across list/grid/context menus
and the single-selection toolbar. Bulk namespace mutations are deliberately not
invented. Viewer/Editor/Owner behavior follows returned allowedCapabilities;
Editor creation/rename/Trash still require the backend's current access checks,
including editable immediate parents for Trash. Copy and move destinations are
owned folders reached from the caller's root, never inferred private ancestry.
Shared root, Search and Trash do not infer a creation destination.

Base UI dialogs retain focus, support Escape, label names and validation, and
prevent duplicate in-flight submission. Names use Java trim, UTF-16 length <=255,
ISO control/path-separator rejection, and Java-compatible blank checks. File rename
preserves the existing extension, including the backend's suffix-appending rule.
The server alone resolves authoritative names and permissions. Restore feedback
uses returned restoredName/name and parentFolderId/folderId, including root fallback.
Trash and permanent deletion have explicit confirmation. Copy cancellation and
partial-copy discard require distinct confirmation.

The destination picker has keyboard-operable folder buttons, Up, independently
paged folder lists and explicit Use this folder selection. It omits nonowned
folders and the source folder, preventing ordinary descent into its subtree;
move also disables selecting the current parent. Concurrent moves and inaccessible
or stale resources remain backend decisions. 400/403/404/409 failures explain
validation, permission, missing/inaccessible resources, collisions, cycles, quota,
copy bounds and lifecycle without parsing provider text or weakening 404 redaction.

Confirmed mutations invalidate current explorer feeds and server quota. Refresh
rereads all previously loaded pages and reconciles selected identities/metadata;
URL folder/filter/view parameters, loaded-page extent and browser history remain
intact. Removed entries lose selection; renamed entries retain it. Other source
and destination views reread on navigation because there is no shared resource cache.

## BFF contract surface

All paths below are under /api/v1. DTOs/statuses derive from generated OpenAPI types.

| Action | Contract |
|---|---|
| Create | POST /folders with name,parentFolderId; 201 folderId |
| Rename | PATCH /folders/{id}; PATCH /files/{id}/name with name |
| Move | PATCH /folders/{id}/move with destinationFolderId; PATCH /files/{id}/move with folderId |
| Trash | DELETE /folders/{id} or /files/{id}; committed 204 |
| Restore | POST /trash/folders/{id}/restore or /files/{id}/restore; returned actual name/location |
| Permanent delete | DELETE /trash/folders/{id}/permanent or /files/{id}/permanent; 202 operationId |
| Deletion status | GET /deletion-operations/{id} |
| File copy | POST /files/{id}/copy with retained Idempotency-Key and destinationFolderId,name |
| File copy controls | GET /copy-operations/{id}; POST its execute,recover,cancel suffixes |
| Folder copy | POST /folders/{id}/copy with retained key and destinationFolderId,name |
| Folder copy controls | GET /folder-copy-operations/{id}; POST its execute,recover,cancel,discard suffixes |

GET and mutation methods coexist on the narrow allowlist. Every POST/PATCH/DELETE
requires exact configured Origin, client admission header and same-origin Fetch
Metadata. Mutation bodies are bounded to 16 KiB, with documented JSON/no-body
media and no mutation queries. Copy admission alone forwards Idempotency-Key.
Browser Authorization/CSRF values are ignored. The server-held JWT authenticates
ordinary backend calls; stored CSRF and refresh cookies remain solely in the
existing serialized auth rotation/logout paths. No browser JWT, localStorage
credential, privileged direct endpoint, mutation retry or new dependency is added.

## Asynchronous work and recovery

The workspace ManagementStore survives client navigation. Its user-scoped
sessionStorage journal records copy keys/original normalized requests, source
IDs, operation IDs and uncertain ordinary-mutation request evidence. It stores
no JWT, CSRF, refresh token, storage key, signed URL or object bytes. When storage
is unavailable, the visible evidence remains in memory. Tab closure/cleared storage
and other devices have no automatic discovery because no operation-list API exists.

Copy admission and controls run exactly once per explicit action. Lost copy
admission can be replayed only with the original key/source/destination/name.
Confirmed rejected admission is labeled separately from an uncertain outcome.
File RESERVED supports explicit execute; UNKNOWN supports positive-evidence
recover. Folder ACTIVE/BLOCKED supports explicit bounded continuation; UNKNOWN
requires recover. Each folder execute/recover call uses the existing maximum four
backend steps; admission enforces 1,000 total members and depth 64. Partial trees
stay hidden until COMPLETED. A lost control followed by a stale status never
grants execute authority; recovery must resolve the retained dispatch first.
Unfamiliar states expose status reads, with no invented mutation authority.

Cancel closes unpublished copy publication and retains partial charges/evidence.
FAILED/CANCELLED folder jobs can separately discard through the existing purge
contract. disposalOperationId is tracked as its own deletion operation, including
when rediscovered by GET after reload. No client deletes storage objects or
implements storage copy shortcuts; new backend object identities remain independent.

Deletion distinguishes ACCEPTED, PROCESSING, BLOCKED, FAILED and
LOGICALLY_COMPLETED, showing reasonCode when present. Accepted work is never
presented as physically cleaned up. Failed or logically completed deletion remains
inspectable because audited operator recovery and retained effects may outlive
metadata. No frontend worker, retention bypass or erasure claim is introduced.

Pending jobs poll GET only, starting after two seconds and backing off to 30
seconds, at most ten reads per action. Timers stop on workspace exit or terminal/
blocked states; in-flight requests have deadlines and cannot schedule after stop.
Received Retry-After delays further explicit operation requests. Polling never
executes, recovers or cancels automatically. Check status starts a new bounded
read window. Reload starts no mutation or polling without an explicit check.

Lost ordinary mutations (including deletion acceptance without an ID) are retained
as uncertain, block form resubmission, and offer affected-view refresh. Repeating
permanent deletion is not a recovery API. Without its accepted operation ID,
operator assistance may be needed. Known backend errors remain distinct from
network uncertainty. Nothing removes uncertain or retained objects for cosmetic
UI cleanup.

## Verification and remaining limits

The frontend README records final counts and reproduction commands. Vitest uses
mocked HTTP/storage and covers validation, collisions, permissions, destinations,
cycles, Trash/restore, deletion states, original-key replay, bounded continuation,
uncertain dispatch, polling cleanup/backoff, disposal discovery, navigation,
selection, focus and the BFF origin/session/CSRF boundary. Backend regression tests
exercise real PostgreSQL lifecycle and concurrency; the existing Garage suite
separately verifies actual copied bytes/independent identity and positive recovery.

The M4 Chrome suite runs desktop, phone and tablet. Its real scenario registers
and signs in through actual browser HTTPS forms, then uses real BFF/backend
folder/file management, copy state and deletion acceptance. The zero-byte Garage
fixture is created by the authenticated BFF HTTP client. Normal autonomous
processors are disabled, so browser deletion verifies acceptance/status, not an
end-to-end purge worker or physical cleanup. Mocked management scenarios use real
Chrome UI with intercepted management responses to exercise admission loss,
UNKNOWN and recovery; they contribute no real storage fault evidence. Light and
dark themes, mobile screenshots, keyboard focus and dialog axe checks are covered.

The existing trusted-HTTPS/CORS/native-expiry/fault/revocation/clock browser-to-
Garage multipart release matrix remains unresolved. Local HTTPS browser auth and
zero-byte copy evidence do not establish multipart PUT CORS. No VM, Garage,
RabbitMQ, PostgreSQL, DNS, clock, certificate or shared infrastructure policy is
modified. Single-process BFF sessions, tab-scoped recovery evidence, explicit
bounded copy continuation and operator-dependent unknown recovery remain limits.
