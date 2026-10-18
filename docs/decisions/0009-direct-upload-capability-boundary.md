# ADR-0009: Multipart capability boundary before direct-upload implementation

- Status: M0 constraints accepted; M1 database foundation implemented, transfer remains planned
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

Accepted part requests can return 200 after complete, abort or URL expiry. The
tested late requests did not change completed bytes, supporting bounded multipart
identity isolation without proving request quiescence or physical reclamation.
Duplicate completion can return NoSuchUpload after success; it is not an outcome
oracle. Signed length/checksum are mandatory capability restrictions, not quotas.

M1 may next establish database identity/idempotency/decision guards. M2–M3 remain
bounded by the matrix and must not introduce blind completion retry or uncertain
cleanup. Browser enablement additionally requires approved HTTPS reachability,
working real-origin CORS and bounded signer/storage clock skew. Current live
configuration does not establish those prerequisites. Multi-node, GC and browser
portability remain unverified. No provider migration or new infrastructure is
selected to compensate for these findings.
