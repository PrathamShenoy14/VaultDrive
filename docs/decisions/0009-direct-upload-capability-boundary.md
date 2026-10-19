# ADR-0009: Multipart capability boundary before direct-upload implementation

Current consolidation: [ADR-0010](0010-backend-consolidation.md) retires synchronous new-upload admission/PUT and foreground receipt creation. Historical phase descriptions below remain records of their original revisions; retained receipts, recovery and locking guarantees still apply. Current new uploads use direct multipart only.

- Status: M0 constraints accepted; M1–M5 backend lifecycle implemented; physical reclamation/browser work deferred
- Date: 2026-10-18
- Evidence: [version-pinned M0 matrix](../garage-multipart-capabilities.md)
- Proposal: [Phase 3A architecture](../direct-upload-architecture.md)

## Decision

Browser milestone extension: [the bounded harness](../browser-multipart-interoperability.md)
implements the client-side exact-body/signed-checksum contract and prepares
explicit-origin Garage CORS with ETag/checksum exposure. Same-origin HTTPS API
transport preserves cookie/CSRF security without backend CORS changes. No capability,
dispatch, lifecycle or immutable identity requirement is relaxed. Actual browser
execution remains blocked; the historical browser prerequisites below remain gates.

Keep Garage 2.3.0 and the existing synchronous path. A future multipart path may
presign only UploadPart, with immutable registered size and SHA-256 for each part.
The backend retains initiation/completion/abort authority. Never permit concurrent
different-content replacement as part of the supported protocol: live M0 testing
observed a selected-ETag/downloaded-content mismatch under that race.

Completion and abandonment must be mutually exclusive durable decisions.
Completion dispatch cannot be reassigned after an uncertain outcome. A successful
storage operation needs separate multipart evidence before READY; V25 PUT success,
V26 cleanup and existing never-written/folder-purge proofs are not extended by
this decision. Explicit unknown outcomes remain retained. No worker, job, endpoint,
migration or production behavior is introduced by M0.

## Consequences and limits

Phase 3C implements the [M1 session foundation](../multipart-upload-sessions.md):
immutable allocation protocol, idempotent reservation and permanent decision
guards. It introduces no multipart storage calls or dispatch authority and rejects
multipart READY/removal until protocol-specific evidence is implemented. All M0
constraints below remain mandatory for later milestones.

Phase 3D adds [M2A backend part transfers](../multipart-part-transfers.md): V28
non-reassignable initiation dispatch permission, exact durable upload-ID binding,
immutable part length/SHA-256 registration, bounded presigning/refresh and
authoritative resume pages. Only UploadPart is presigned. Unknown initiation
remains INITIATING without retry. M2A itself added no completion/abort/READY or cleanup.

Phase 3E adds [M3 completion and explicit recovery](../multipart-completion-recovery.md).
V29 freezes the authoritative validated manifest, retains one immutable completion
dispatch identity, and commits a separate multipart receipt before READY. Positive
checksum-enabled exact-key HEAD recovery requires original binding, backend-set
session/initiation metadata, exact size/type, the verified Garage composite SHA-256
and the retained sole completion manifest. It relies explicitly on the trusted
single-control-plane/nonreuse assumption; HEAD itself exposes neither upload ID
nor manifest hash. No negative observation authorizes redispatch or cleanup.

Accepted part requests can return 200 after complete, abort or URL expiry. The
tested late requests did not change completed bytes, supporting bounded multipart
identity isolation without proving request quiescence or physical reclamation.
Duplicate completion can return NoSuchUpload after success; it is not an outcome
oracle. Signed length/checksum are mandatory capability restrictions, not quotas.

M4 adds [cancellation and explicit bounded expiry](../multipart-cancellation-expiry.md).
V30 retains abandonment intent, exact identity, one non-reassignable abort dispatch
and terminal acknowledgement. A lost abort response remains UNKNOWN without
probing absence or redispatching. HTTP 204 permits control-plane cancellation but
supplies no physical-removal proof. Expiry uses PostgreSQL time and records intent
without storage I/O; no autonomous execution is added. Files/versions and folder
purge snapshots remain unchanged, including their multipart blockers.

Later reconciliation and removal work remains bounded by the matrix and must not
introduce blind completion/abort retry or uncertain cleanup. Browser
enablement additionally requires approved HTTPS reachability,
working real-origin CORS and bounded signer/storage clock skew. Current live
configuration does not establish those prerequisites. Multi-node, GC and browser
portability remain unverified. No provider migration or new infrastructure is
selected to compensate for these findings.

## M5 lifecycle extension

Accept [the separate logical and physical guarantees](../multipart-purge-compatibility.md).
Abandonment releases the filename through FAILED without authorizing storage cleanup.
A sealed permanent folder intent can dispose of unfinished multipart file metadata
while preserving all storage identities and outstanding dispatch/outcome evidence.
Its immutable logical receipt permits folder finalization but cannot become a byte
or never-written proof. This avoids permanent user-facing folder blockage caused
solely by unavailable writer-quiescence evidence. READY multipart deletion requires
its own completion receipt and uses the existing immutable-key purge path. Late
UploadPart success cannot authorize a new completion or a different final object
under the accepted sole-control-plane protocol; no generation fence is invented.

A late permitted Complete may create an object after logical folder removal. Retain
that allocation and receipt with unresolved reclamation; never repeat Complete or
Abort, infer safety from age/absence/abort acknowledgement, or automatically upgrade
logical proof. Garage block GC, quiescence, multi-node behavior and cleanup repair
remain outside the established guarantee. No autonomous defaults change.

## Sharing M3 delegated authority

[Sharing M3](../sharing-m3.md) supersedes owner-only session authorization. Immutable
initiators and destination-derived ownership keep collaboration separate from
storage identity. Revocation blocks new admissions/dispatch and signed-URL responses;
already admitted calls can retain exact evidence and finalize eligible owner files.
Outstanding part URLs cannot be recalled. This changes no Garage capability,
uncertain-dispatch, lifecycle, writer-quiescence or physical reclamation guarantee.
