# ADR-0009: Multipart capability boundary before direct-upload implementation

- Status: M0 constraints accepted; M1/M2A and M3 completion/evidence/READY implemented
- Date: 2026-10-18
- Evidence: [version-pinned M0 matrix](../garage-multipart-capabilities.md)
- Proposal: [Phase 3A architecture](../direct-upload-architecture.md)

## Decision

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

Later cancellation, reconciliation and removal work remains bounded by the matrix
and must not introduce blind completion retry or uncertain cleanup. Browser
enablement additionally requires approved HTTPS reachability,
working real-origin CORS and bounded signer/storage clock skew. Current live
configuration does not establish those prerequisites. Multi-node, GC and browser
portability remain unverified. No provider migration or new infrastructure is
selected to compensate for these findings.
