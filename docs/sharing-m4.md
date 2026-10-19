# Sharing M4: secure file Viewer links

Implemented under the [approved sharing design](sharing-permissions-design.md).
M4 adds file-only bearer capabilities. Activity, notifications, frontend work and
legacy upload retirement remain outside this milestone.

## Migration and secret contract

V34 creates `share_links` with a composite `(file_id, owner_id)` foreign key to
`files`, cascading only when file metadata is removed. A trigger makes identity,
file/owner binding, token hash, creation and expiry immutable. PostgreSQL enforces
a unique 32-byte hash, finite creation/expiry, positive lifetime of at most seven
days, and a single irreversible revocation with version `0 -> 1`. Expiry is derived;
no scheduler or lifecycle/file version update is needed. Existing migrations,
storage identities, allocations, multipart and purge protocols are unchanged.

`SecureRandom` supplies 32 random bytes (256 bits), encoded as 43 canonical,
unpadded base64url characters. Only SHA-256 of the returned ASCII secret is stored.
Creation returns the secret once; listing and public metadata never include it or
the hash. The creation response's diagnostic `toString()` redacts the secret.
Renewal creates a separate identity and secret. A forgotten secret cannot be recovered.

## HTTP contracts

Owner routes require the existing JWT. Visible collaborators receive 403;
strangers/unknown targets receive 404 and missing/invalid JWT receives 401.

| Route | Request and response |
|---|---|
| `POST /api/v1/files/{fileId}/share-links` | JSON `{}` or `{"expiresAt":"<UTC instant>"}`; 201 with `{link, secret}` |
| `GET /api/v1/files/{fileId}/share-links` | Array of `{id, createdAt, expiresAt, revokedAt, version}` including expired/revoked links |
| `DELETE /api/v1/files/{fileId}/share-links/{linkId}` | 204; repeated or unknown link revocation within an authorized file is a no-op |
| `GET /api/v1/public/share-links/{linkId}` | `{name, contentType, sizeBytes}` |
| `GET /api/v1/public/share-links/{linkId}/download` | Backend byte stream, attachment disposition, `application/octet-stream`, metadata content length |

Expiry defaults to PostgreSQL time plus 24 hours. Explicit expiry must be strictly
future and at most seven days from creation; invalid deadlines return 400.
Input instants are reduced to PostgreSQL microsecond precision. Public authorization
requires `expires_at > statement_timestamp()` after coordination, so equality is
expired and time spent waiting for a lock cannot extend validity.

Both public GETs require exactly one `X-VaultDrive-Share-Token` header. Query strings
are rejected, even with a valid header. Malformed/missing/noncanonical/wrong secrets,
unknown IDs, expired/revoked links and inaccessible lifecycle/ancestry all produce
the same 404 JSON: `{"message":"Share link not found"}`. Malformed public IDs,
unsupported public subroutes and non-GET methods receive the same result.
Saturation can return 429 before capability validation, independent of validity.
Storage-open failures return a sanitized 502; interrupted streams close their input.

## Security and coordination

A separate, stateless security chain permits only the two public GET patterns.
It does not authenticate JWTs or turn link secrets into a principal. A link cannot
authorize ordinary file/folder routes, listing/traversal, uploads, Editor actions,
session operations or sharing management. Public DTOs contain no owner, file,
parent, sibling, grant, storage-key or link-management identity.

Link creation/revocation takes the resource owner's exclusive hierarchy lock.
Owner listing and public authorization take the shared lock. Only immutable owner
identity is read before coordination. Afterwards one scalar statement binds link
hash, revocation, expiry, READY file state, current location and complete ancestry
through the existing bounded resolver. This rejects Trash, pending purge, missing
or cross-owner ancestry, cycles and excessive depth. Compatible file moves cannot
split authorization across snapshots. File versions and lifecycle timestamps do
not change when links change.

Trash suspends access to the file or any descendant of a trashed ancestor. Owners
can inspect/revoke links while trashed; new links require an active target (409 in
Trash). Restore reactivates only still-valid links, including root-fallback restore.
Pending purge blocks reads and all link management, preserving subtree barriers.

Authorization commits before storage opens. The controller rejects ambient
transactions and streams synchronously through `ObjectStorageService` without
database locks. Already authorized streams may finish after revocation, expiry,
Trash or purge. Subsequent requests reauthorize. No presigned GET URL is issued.

All link routes, including errors and owner creation/list/revoke, receive
`Cache-Control: no-store`, `Referrer-Policy: no-referrer` and
`X-Content-Type-Options: nosniff`. Downloads always use attachment disposition
with encoded UTF-8 filenames and an octet-stream media type.

## Bounded admission and deployment limits

The simplest local mechanism is a process-wide token bucket plus nonblocking
semaphores. Defaults are 30 public requests/second, burst 60, at most 16 public
requests in flight, and four downloads in flight. Request admission precedes
database work; download admission precedes authorization/storage and lasts through
response streaming. Exhaustion returns no-store 429 with `Retry-After: 1`. Closing
on success, denial, storage failure or client disconnect releases capacity. There
are no attacker-keyed maps, wait queues, client-IP trust rules or async task queues.

Positive settings are configurable through `vaultdrive.share-links.requests-per-second`,
`request-burst`, `max-requests` and `max-downloads`. These are per-process bounds,
not distributed quotas or fairness guarantees. Slow clients can occupy slots;
deployment must supply appropriate connection/response timeouts and edge admission.
Throughput and multi-instance fairness are unmeasured.

Production requires HTTPS. Exclude `X-VaultDrive-Share-Token`, creation response
bodies and capability-bearing input from proxy/APM/debug logging. Application code
logs no secrets and uses controlled public errors; arbitrary external request/body
logging is a deployment responsibility. A future client can transport the secret
in a URL fragment and send the header, but no client/fragment implementation is added.

## Verification

`ShareLinkTokensTest`, `ShareLinkAdmissionTest` and `ShareLinkHttpFilterTest` cover
the random construction, canonical format, unique samples, exact SHA-256,
rate refill/burst, positive configuration and bounded concurrency. Sampling is not
a statistical proof of entropy; the guarantee comes from the SecureRandom construction.

Real PostgreSQL `ShareLinkIntegrationTest` covers constraints, stored hashes,
owner-only management, one-time secrets, expiry/default/maximum boundaries,
permanent revocation, no-store/redaction, all denial shapes, current ancestry,
Trash/restore/moves, READY/purge gates and cascading file removal.
`ShareLinkConcurrencyIntegrationTest` observes actual PostgreSQL advisory-lock
waits for both revoke/read, create/Trash and revoke/purge orders, and expiry during
a lock wait. `ShareLinkDownloadIntegrationTest` covers streaming outside transactions,
revocation after admission, no storage calls on denial, capacity rejection/release,
sanitized storage failure and client disconnect. `ShareLinkGarageIntegrationTest`
uses real HTTP/JWT, PostgreSQL and Garage bytes, including Trash/restore/revoke.

Commands load `backend/.env.local.ps1` in the Maven shell and use:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m4_verification
-Dspring.flyway.schemas=sharing_m4_verification
-Dspring.flyway.default-schema=sharing_m4_verification
```

Focused selection: `-Dtest=ShareLink*Test`; full suite: `mvnw.cmd test` with the same
schema arguments. Tests with their own schema isolation keep it. No shared schema
is cleaned and retained test allocations are preserved. The historical default
schema V26 checksum mismatch remains untouched. Async production flags remain
disabled; live broker regressions use only the existing isolated test vhost and
documented localhost SSH tunnel.

Executed on 2026-10-19 against PostgreSQL 17 and inspected Garage image
`dxflrs/garage:v2.3.0`:

- Focused M4 command: **30 passed**, no failures/errors/skips.
- Full backend suite: **1,280 tests, 1,232 passed, 48 skipped**, zero failures/errors.
  This includes all M4 checks, existing sharing/lifecycle/concurrency regressions,
  real Garage storage/download checks and seven isolated live RabbitMQ tests.
- Supplemental Garage regression command: **27 passed**, no failures/errors/skips.
  Selection: `GarageMultipartTransferIntegrationTest`,
  `GarageMultipartCompletionIntegrationTest`, `GarageMultipartCompletionEvidenceTest`,
  `GarageMultipartCancellationIntegrationTest`, `GarageMultipartPurgeIntegrationTest`.
  It sets the four documented `GARAGE_MULTIPART_{TRANSFER,COMPLETION,CANCELLATION,PURGE}_TEST=true`
  gates and `GARAGE_MULTIPART_VERSION=2.3.0`, loading the private environment and
  using the schema arguments above. Disposable fixtures keep their existing
  journals and uncertainty-retention rules; no retained allocations are swept.
- `git diff --check` and local Markdown links passed.

Of the full-suite skips, those 27 Garage checks passed in the supplemental run.
The remaining **21 checks were not run**:

| Suite | Skipped | Separate gate |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` |
| `GarageMultipartJournalRecoveryTest` | 1 | reviewed `GARAGE_MULTIPART_REVIEWED_RUN` UUID |

Their process, capability and journal-review prerequisites are unchanged. No
browser/TLS/CORS, clock-skew, late-writer or physical reclamation guarantee is added.
