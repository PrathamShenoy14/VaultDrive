# Sharing and permissions design

Status: Approved design; **M1 (grants and Viewer access), M2 (Editor content operations), M3 (delegated multipart) and M4 (file Viewer links) are implemented**. M5-M6 remain planned.

Source: Revised sharing/permissions plan in `Pasted text(9).txt` from the "VaultDrive Day One Plan" conversation, recorded on 2026-10-19. This document preserves that plan and the decision to retire legacy synchronous upload.

Scope: Approved six-milestone implementation boundary. See [the M1 implementation contract](sharing-m1.md) for V32, implemented routes, concurrency guarantees and verification limits. See [the M2 contract](sharing-m2.md) for Editor mutations and verification. See [the M3 contract](sharing-m3.md) for delegated multipart and [the M4 contract](sharing-m4.md) for secure file Viewer links. Activity and notifications below remain design only.

The revised MVP keeps **PostgreSQL direct grants, downward inheritance, and the strongest applicable permission**. Editors upload directly to Garage through the existing multipart protocol. Ownership, sharing control, moves, restore, and permanent deletion remain with the resource owner.

This supersedes the earlier owner-only multipart proposal and the proposed `server_upload_actors` table. Sharing M3 implements delegated multipart; synchronous upload remains owner-only.

## Keep ownership and collaboration separate.

- New root resources are private. Resources created inside a shared folder inherit that folder’s access.
- `OWNER` comes from the resource’s immutable `owner_id`; direct grants contain only `VIEWER` or `EDITOR`.
- Folder grants apply to the folder and all descendants.
- Effective access is the strongest direct or inherited grant.
- A direct Viewer grant cannot reduce inherited Editor access.
- There are no Commenters, deny rules, restricted subfolders, ownership transfers, or cross-owner moves.
- Uploading or creating a folder inside someone else’s editable folder creates an **owner-owned resource**. Being its creator grants no independent access.

Authorization must distinguish `actorId`, derived from the JWT, from `ownerId`, derived from the resource or destination. Clients cannot choose ownership.

**Use these database changes.** All changes require new Flyway migrations after V31.

| Table | Proposed structure |
|---|---|
| `resource_grants` | `id`, `owner_id`, nullable `file_id`, nullable `folder_id`, `grantee_user_id`, `role`, `version`, creation/update timestamps |
| Existing `upload_sessions` | Add immutable `initiator_user_id`; retain `owner_id` as the resource/storage owner |
| `share_links` | `id`, `owner_id`, `file_id`, `token_hash`, `expires_at`, `revoked_at`, `version`, `created_at` |
| Later: `permission_activity_events` | Immutable event identity, actor, owner, resource, grant/link subject, affected recipient, old/new role, subject version, timestamp |
| Later: `notifications` | `id`, `source_event_id`, `recipient_user_id`, `type`, `created_at`, `read_at` |

For grants, enforce:

- Exactly one target: `num_nonnulls(file_id, folder_id) = 1`.
- `role IN ('VIEWER', 'EDITOR')`.
- One direct grant per target and recipient, using partial unique indexes.
- Registered recipients through a user foreign key; reject self-grants.
- Composite resource/owner foreign keys. Add `UNIQUE(files.id, files.owner_id)`; folders already have the equivalent.
- Immutable target, owner, and recipient; optimistic versioning for role changes.
- A recipient-first index for “Shared with me.”

Grant revocation deletes the direct grant. A later regrant receives a new grant ID. Resource removal can cascade to grants and links, while retained storage/session/purge evidence remains untouched.

For multipart sessions:

- Backfill `initiator_user_id = owner_id` for existing sessions.
- Replace owner/key uniqueness with **`(owner_id, initiator_user_id, idempotency_key)`**.
- Include destination owner in the request fingerprint alongside the existing destination and upload metadata.
- Preserve the existing owner/key advisory reservation lock. Different initiators using the same key may conservatively serialize but receive independent sessions.
- Replaying a reservation rechecks current access before returning session information. It never extends expiry or grants new capabilities.

The new migration must handle the existing session immutability trigger deliberately during backfill, then enforce initiator immutability. Existing storage identities, manifests, receipts, deadlines, decisions, and dispatch IDs must remain unchanged.

Do not add creator columns to `files`: [V26’s cleanup snapshot](../backend/src/main/resources/db/migration/V26__known_success_upload_cleanup.sql) includes the whole file row. Keeping upload initiator identity in `upload_sessions` avoids changing that snapshot contract.

**Resolve permissions from current resource state.** A bounded recursive PostgreSQL query should:

1. Validate resource readiness/lifecycle and its complete owner-consistent ancestry.
2. Reject missing ancestry, cycles, deleted ancestors, and pending purge.
3. Return Owner for the owner.
4. Otherwise combine the actor’s direct grant with grants on ancestor folders.
5. Return the strongest role, or no access.

Direct sharing does not require access to the resource’s parents. However, those parents must remain structurally accessible. Redact inaccessible parent references and breadcrumbs from responses.

No copied descendant grants, closure table, authorization cache, or generic RBAC framework is needed.

## Use this authorization matrix.

| Operation | Owner | Viewer | Editor | Viewer link |
|---|---:|---:|---:|---:|
| Read accessible metadata | Yes | Yes | Yes | Linked file only |
| List folder contents | Yes | Yes | Yes | No |
| Download active READY file | Yes | Yes | Yes | Linked file only |
| Create folder | Yes | No | In editable folder | No |
| Upload through multipart | Yes | No | Into editable folder | No |
| Rename | Yes | No | Yes | No |
| Trash content | Yes | No | Within editable folder | No |
| Move file/folder | Yes | No | No | No |
| View owner’s Trash | Yes | No | No | No |
| Restore | Yes | No | No | No |
| Permanently delete | Yes | No | No | No |
| Manage grants or links | Yes | No | No | No |
| Inspect complete sharing list | Yes | No | No | No |

Editor trash requires Editor access to the target’s **immediate parent**. This permits managing content inside an editable folder, including content created by others, while preventing removal of a directly shared entry from an inaccessible parent. The owner’s null/root namespace is owner-only.

Rename requires Editor access to the target, including a directly shared file or folder.

All MVP client uploads use multipart sessions. The legacy synchronous upload endpoint must not be extended and is slated for retirement. It may remain owner-only temporarily for compatibility, but Editors receive no synchronous fallback. Retirement is a subsequent implementation task; this design document makes no endpoint changes. Existing multipart size/geometry limits remain; zero-byte collaborator uploads need a separate supported protocol change.

## Delegated multipart authority belongs to the initiating Editor and the owner.

On reservation:

- Resolve the destination folder’s owner.
- Require current Editor access to that folder.
- Atomically create the owner-owned file, allocation, session, and immutable initiator identity.
- Generate the storage key using the **owner’s UUID**, never the uploader’s UUID.

For session operations:

| Caller | Session authority |
|---|---|
| Resource owner | May manage the session within existing lifecycle rules |
| Original initiator | May operate it while retaining current Editor access to the destination |
| Another Editor | Cannot inspect or operate someone else’s session |
| Viewer, stranger, link holder | No session authority |

The initiator can initiate, request/refresh part URLs, list parts, complete, recover, and cancel their own upload while authorized. Cancelling an unfinished upload is abandonment of a reservation, not permission to permanently delete a READY file.

After completion, ordinary file access follows its current location and grants. Creator identity never bypasses revocation.

Every capability-producing transaction must freshly check session membership, effective Editor permission, ancestry, expiry, and existing lifecycle eligibility. Controller-only checks are insufficient.

## Make revocation races explicit at each durable boundary.

| Race | Required outcome |
|---|---|
| Revocation wins before reservation | No file/allocation/session is created |
| Revocation wins before initiation dispatch permission | No new CreateMultipartUpload dispatch |
| Revocation occurs during URL signing | Final authorization rejects the URL response |
| Revocation wins before completion dispatch permission | No new CompleteMultipartUpload dispatch |
| Completion dispatch permission commits first | That one permitted call may finish |
| Revocation precedes a later session request | Request is denied, including identical retries |

The final authorization after URL signing is the response boundary. Revocation after that boundary may race with delivery of the already authorized response.

A frozen completion manifest is **not** sufficient authority to dispatch. The separate dispatch transaction must recheck the actor’s current permission. If revocation wins between freezing and dispatch, the frozen decision remains retained; it must not be reversed into abandonment. The owner can use existing recovery paths where lifecycle and expiry rules permit.

If dispatch permission committed before revocation, trusted backend processing may record the exact result, persist a positive receipt, and finalize an otherwise eligible **owner-owned** file. Current uploader permission is not required to preserve that already permitted operation’s evidence.

This is the chosen in-flight contract: **revocation stops new authority; it does not recall an already admitted storage operation.** A public response after external work must recheck caller access before returning session details. Denial does not roll back a storage effect.

Revocation does not automatically cancel every affected session or traverse a subtree. Without sufficient permission, sessions remain inaccessible to the uploader; owner actions and existing expiry/recovery mechanisms handle them. Regranting Editor access can permit resumption of an unchanged, still-eligible session.

## Outstanding presigned URLs remain bounded bearer capabilities.

PostgreSQL cannot retract an already issued Garage signature. A revoked uploader may still submit parts using outstanding URLs, but cannot obtain fresh URLs or authorize completion after revocation wins.

Preserve the existing restrictions:

- Presign only UploadPart.
- Bind exact upload identity, part number, length, and immutable SHA-256.
- Keep initiation, completion, and abort backend-only.
- Keep URLs bounded by both the short signing lifetime and session expiry.
- Recheck permission and session revision after signing.
- Never treat expiry or abort acknowledgement as proof that all writers stopped.

Retain [ADR-0009’s](decisions/0009-direct-upload-capability-boundary.md) measured Garage limits, including unresolved clock skew and late requests. Browser release still requires working HTTPS, real-origin CORS, and bounded clock behavior.

No permission change alters storage keys, reuses allocations, repeats uncertain Create/Complete/Abort calls, or authorizes compensating deletion. READY still requires the existing multipart receipt and fresh lifecycle validation. M5’s unfinished logical disposal remains distinct from physical reclamation.

## Keep move, Trash, and restore semantics predictable.

- Owner moves preserve direct grants and recompute inheritance from the destination.
- Folder moves apply that rule to the whole subtree without rewriting grants.
- Trashing a resource or ancestor suspends collaborator and link access.
- Restore reactivates retained grants and unexpired, unrevoked links; inheritance follows the restored location.
- Root-fallback restore removes former inherited access but retains direct grants.
- Pending purge preserves all existing subtree barriers and freezes sharing mutations.
- Owners may inspect/revoke grants and links while trashed, before pending purge, so they can control what restore will reactivate.
- New grants, role increases, and links require an active accessible target.

Moving a resource out of a shared folder does not remove an independent direct grant on that resource.

## Use the existing hierarchy coordinator for permission consistency.

| Path | Coordination |
|---|---|
| Grant/link mutations | Exclusive resource-owner hierarchy lock |
| Folder mutations | Existing exclusive owner hierarchy lock |
| File mutations and multipart transactions | Shared resource-owner hierarchy lock |
| Owner-scoped authorization reads | Short shared hierarchy transaction |
| Storage calls/signing/streaming | Outside metadata transactions and locks |

Preserve [ADR-0007’s](decisions/0007-concurrency-locking.md) ordering:

```text
owner hierarchy
→ fresh file/resource
→ allocation
→ session
→ ordered parts
→ completion/cancellation progress and receipts
```

Permission checks occur after the coordinating locks and fresh reads. Grant mutations serialize through the exclusive hierarchy lock; no separate lock on every ancestor grant is needed.

Keep permission checks separate from trusted evidence recording. Revocation must block new public authority without preventing retention of facts from a previously permitted call.

Preserve file optimistic versions, conditional status transitions, restore namespace ordering, and worker job-fencing order. ACL changes must not change file versions or lifecycle timestamps.

For file reads, fetch location, ancestry, lifecycle, and effective permission in one statement snapshot. Shared hierarchy locks alone do not serialize same-file moves. “Shared with me” must also filter authorization before pagination.

## Add these API contracts while preserving existing resource routes.

| Endpoint | Contract |
|---|---|
| `POST /api/v1/{files\|folders}/{id}/grants` | Owner supplies exact registered email and Viewer/Editor role; `201` |
| `GET /api/v1/{files\|folders}/{id}/grants` | Owner receives direct grants and inherited sources |
| `PATCH .../grants/{grantId}` | Change a direct role using `If-Match`; `200` |
| `DELETE .../grants/{grantId}` | Revoke a direct grant; `204` |
| `GET /api/v1/shared-with-me` | Paginated directly granted entry points |
| `GET /api/v1/files/{id}` | Add file metadata retrieval |
| Existing `/api/v1/upload-sessions` routes | Accept owner or authorized original initiator |
| `POST /api/v1/files/{id}/share-links` | Create expiring Viewer link; `201` |
| `GET /api/v1/files/{id}/share-links` | Owner lists link metadata; never returns stored secrets |
| `DELETE /api/v1/files/{id}/share-links/{linkId}` | Permanently revoke; `204` |

Resource responses include effective role and allowed capabilities. Session responses identify `ownerId` and `initiatorUserId`; clients cannot submit either as authority.

Preserve existing multipart `200`/`202` progress semantics. Authorization failure must never cause a retry with a fresh storage dispatch identity.

Use:

- `401` for missing/invalid JWT on authenticated routes.
- `404` for unknown, inaccessible, or unauthorized session/resource existence.
- `403` when a caller can see a resource but lacks an operation privilege.
- `409` for namespace, fingerprint, or lifecycle conflicts.
- `412` for stale `If-Match`; `428` when required preconditions are missing.

Authorize the target before recipient lookup. Use exact normalized email, not a user directory. Inherited grants are changed at their source rather than deleted at a child.

## Provide file-only Viewer links through the backend.

The minimal link is an `ANYONE_WITH_LINK` bearer capability with a fixed Viewer role. Registered-user sharing remains the direct-grant mechanism.

- Generate a random 256-bit secret; store only its SHA-256 hash.
- Return the secret once.
- Require expiry; suggested default 24 hours, maximum seven days.
- Make revocation permanent; renewal creates a new link.
- Enforce a composite file/owner foreign key and unique token hash.
- Keep link role fixed in the API rather than adding another role model.

Use narrowly public metadata/download routes such as:

```text
GET /api/v1/public/share-links/{linkId}
GET /api/v1/public/share-links/{linkId}/download
```

Supply the secret in a dedicated header. A future client can receive it through a URL fragment, avoiding query-string logging. Invalid, expired, revoked, or lifecycle-blocked links return the same `404`.

These routes revalidate PostgreSQL and stream through the existing storage abstraction. They issue no presigned GET URL and authorize no ordinary API, folder listing, upload, parent traversal, or permission inspection.

Use `no-store`, attachment downloads, token redaction, HTTPS, and bounded public request/download admission. Already authorized streams may finish after revocation.

## Design activity and notifications now, implement them in their dedicated milestones.

Record immutable events for:

- Direct grant creation, role change, and revocation.
- File link creation and revocation.

Each event identifies the owner, acting user, resource, grant/link ID, affected recipient where applicable, previous/new direct role, subject version, and timestamp. Store no link secret or signed URL.

Record one event at the changed source, not one per inherited descendant. No-op updates and repeated revocations produce no additional event. Automatic expiry is derived from the deadline; it does not require a new scheduler merely to emit an event.

When implemented, the permission mutation, activity event, and outbox record commit atomically. Activity survives resource deletion and is owner-visible in this MVP.

Sharing notifications should initially be **in-app notifications to the direct recipient** for grant creation, change, and revocation:

- Deduplicate with `UNIQUE(source_event_id, recipient_user_id)`.
- Acknowledge delivery only after notification persistence commits.
- Recheck current access when rendering resource details.
- Suppress obsolete actionable notifications after revoke/regrant or later changes.
- A notification never grants access.
- Keep historical content generic when the recipient no longer has access.
- Defer email and inherited-descendant fan-out.

The existing transport publishes everything to the purge queue, whose consumer rejects other event types. Therefore, the dedicated milestone must add event-specific routing before publishing sharing events. Reuse the outbox machinery, but do not send sharing events through the purge contract.

## Implement this in six bounded milestones.

| Milestone | Testable acceptance boundary |
|---|---|
| 1. Grants and Viewer access — implemented | Constraints, resolver, owner grant APIs, inherited reads/list/download, shared entry points, parent redaction |
| 2. Editor content operations (implemented) | Owner-assigned folder creation, rename, conditional trash, and owner-only move/restore/purge |
| 3. Delegated multipart (implemented) | Initiator migration, actor-scoped idempotency, all session authorization gates, revocation races, owner recovery |
| 4. File Viewer links (implemented) | Expiry, permanent revocation, lifecycle blocking, token secrecy, backend streaming; [M4 contract](sharing-m4.md) |
| 5. Permission activity | Atomic mutation/activity/outbox persistence and isolated event routing |
| 6. Sharing notifications | Idempotent in-app delivery, stale-event handling, recipient privacy |

Milestone 3 must cover both race orders at reservation, initiation permission, URL-response authorization, manifest freeze, completion dispatch, and cancellation. Also verify:

- Two Editors using the same idempotency key remain independent.
- Another Editor cannot operate the initiator’s session.
- Revocation after dispatch preserves evidence without restoring caller access.
- Folder moves/trash/purge retain fresh validation.
- Migration preserves existing session decisions and storage receipts.
- Existing Garage, cleanup, outbox, and purge regressions still pass.

Run focused tests and the relevant full suite for each implementation slice, using real PostgreSQL for races and Garage for storage outcomes.

The remaining implementation limits are the existing multipart size policy, browser/TLS/CORS/clock prerequisites, and the explicitly deferred activity and notification milestones.
