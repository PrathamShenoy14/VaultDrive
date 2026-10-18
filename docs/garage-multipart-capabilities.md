# Garage multipart capability matrix — Phase 3B / M0

Characterization only; no production sessions, endpoints, migrations, upload/purge/
cleanup changes or autonomous enablement. The [Phase 3A report](direct-upload-architecture.md)
remains a proposal; findings below refine its experimental assumptions.

## Version and evidence boundary

Inspected on 2026-10-18 (JVM clock). Read-only SSH confirmed running image
`dxflrs/garage:v2.3.0` and binary `garage v2.3.0`. AWS SDK **2.48.0**, Java **21**,
production `S3StorageConfig` / Apache client, path-style access, one SDK attempt,
production connection/acquisition/socket timeouts and checksum WHEN_REQUIRED.
No dependency or production configuration changed.

The probe uses the existing configured bucket through `http://localhost:3900`.
All writes use new UUID allocations under `integration-tests/multipart-m0/<run>/`.
There is no Spring application context, datasource or Flyway in the new tests.
The existing B4A regression class independently owns its fresh PostgreSQL schema.

Evidence labels:

- **Live:** real request responses and downloaded bytes in this environment.
- **Source:** tagged implementation expectation, not a universal storage guarantee.
- **Untested:** no experiment establishes the claim.

## Capability matrix

| Experiment | Live result | Protocol consequence |
|---|---|---|
| 1. Create / UploadPart / ListParts / Complete / Abort | Create, part, list and complete returned HTTP 200; abort returned 204. Completed GET bytes and backend-set run metadata matched. | Core multipart path works in the inspected deployment. |
| 2. Browser-shaped presigned UploadPart | HTTP/1.1 request with Origin, signed `x-amz-checksum-sha256`, automatic Host and Content-Length returned 200. Signed headers were `content-length;host;x-amz-checksum-sha256`. No Authorization header or permanent secret went to the caller. | Blob uploads must supply the checksum and let the browser generate matching length. Actual browser execution remains untested: no browser surface was connected. |
| 3. Expiry and tampering | Expired correctly signed request: 400 InvalidRequest. Tampered signature, part number and upload ID: 403 AccessDenied. Correctly signed nonexistent upload ID: 404 NoSuchUpload. Correctly signed **part number 0 was accepted (200)**. | Sign exact identities and validate the application part range; do not rely on Garage to enforce 1-based numbering. |
| 4. SHA-256 and length | Wrong bytes with matching length/digest header: 400 InvalidDigest, no completed listed part. Changing actual body length by ±1 while retaining signed Content-Length: 403 AccessDenied. Omitting length from signed headers allowed a 17-byte body despite the caller's 4096-byte expectation. | Checksum and signed length are enforced, not merely declared. Expected file/part geometry still belongs to PostgreSQL. An unsigned declaration is no bound. |
| 5. Replay / sequential replacement | Same URL and bytes returned 200 again with matching ETag. With no checksum binding, the same URL accepted different bytes of equal length; completion returned replacement bytes. Signed checksum rejected changed bytes. | URLs are reusable capabilities. Freeze size and SHA-256 for a part before issuing its first URL; every refresh preserves both. |
| 6. Concurrent parts | Different part numbers completed with the exact concatenated bytes. Competing different-byte requests for one number exposed an intermittent **selected ETag versus downloaded-content mismatch** in an earlier run. Ten checksum-bound same-content races all completed with expected bytes. | Concurrent arbitrary replacement is **unsupported**. Immutable checksum/size is mandatory, not an optional integrity enhancement. Ten rounds are bounded evidence, not a distributed correctness proof. |
| 7. In-flight part versus complete | Gate received Garage's 100 Continue, sent one byte, withheld the rest; Complete returned 200 before release. Both a new late part and a late replacement subsequently returned **200**. | Completion does not mean every accepted part handler has ended. |
| 8. In-flight part versus abort | Same gate; Abort returned 204 before release; the held part subsequently returned **200**. Fresh replay then returned 404 NoSuchUpload; ListParts returned NoSuchUpload. | Abort acknowledgement is not request-termination or physical-reclamation proof. |
| 9. Late mutation of completed bytes | GET before and after both late part responses returned the original completed bytes. Fresh old-URL requests returned 404 and bytes remained unchanged. | Supports upload-ID/final-object isolation for these interleavings; does not establish every multi-node/replication/GC case. |
| 10. Completion loss / retries / ambiguity | A one-request TCP proxy forwarded the production SDK completion to Garage, consumed real 200/CompleteMultipartUploadResult, then dropped the entire downstream response. Caller got SdkClientException; independent GET found exact bytes. Duplicate complete returned 404 NoSuchUpload without changing bytes. Wrong-ETag completion returned 400 InvalidPart; the corrected request then completed with exact bytes. | A failed caller can have a completed object. NoSuchUpload alone is not success or absence proof. Retain COMPLETION_UNKNOWN; no blind repeat, abort or DELETE. Only the explicitly verified rejection is a bounded retry case. |
| 11. ListParts pagination | Five parts with maxParts=2 produced three pages, increasing markers, exact numbers/sizes, opaque ETags and matching SHA-256 metadata. HEAD of completed checksum upload returned size/checksum/run metadata; VersionId was null. | Resume from authoritative paginated parts. Do not treat ETag as a full-file checksum or invent a generation DELETE fence. |
| 12. Origin / HTTPS / CORS | Synthetic origin `http://localhost:3000`: OPTIONS returned 403 AccessDenied with wildcard Allow-Origin but **no Allow-Headers**; GetBucketCors returned 404 NoSuchCORSConfiguration. Configured endpoint is HTTP. | Browser release is blocked pending approved origin/CORS/TLS prerequisites; wildcard error headers are not a working upload CORS policy. |

An accepted part also returned 200 after its URL deadline passed; the same URL on
a fresh request returned 400. The test deliberately waits across an authorization
deadline, **never** using that wait as proof of writer termination.

## Important qualifications and source comparison

Tagged [multipart code](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/multipart.rs)
checks upload state before streaming and assembles a separate final version.
Live late-200 responses and unchanged completed bytes agree with that model.
The gate establishes request-header acknowledgement, a transmitted body prefix,
completion/abort acknowledgement, then release. It is not an injected server-side
latch and does not directly observe the internal read point. The successful late
responses establish actual accepted work continuing after the terminal operation.

The different-byte same-part mismatch is a verified safety failure, not an assumed
last-writer winner. The final bounded run reproduced it in **7 of 20 rounds**;
all ten immutable-checksum rounds returned the expected bytes. Final run journal:
`e5cef56c-f346-413c-89da-c7d9a5d650ec`. Source-level suspicion is timestamp/part-version merge behavior
in the [MPU model](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/model/s3/mpu_table.rs);
the precise cause was not proven. The successful final characterization suite can
record either match or mismatch, while explicitly classifying arbitrary competing
replacement as unsupported. It requires actual bytes to match one of the submitted
payloads; the checksum-bound protocol has strict expected-byte assertions.

Signed length binds HTTP framing through signature validation. It is not an
application quota or a claim about immediate storage allocation limits. Invalid
payloads and replay can still consume temporary resources. Browser-generated
Content-Length portability, HTTP/2, proxy behavior and real-origin fetch remain
untested. A Blob with an empty MIME type avoids introducing an unplanned signed
Content-Type requirement.

Abort/fresh NoSuchUpload and absent completed objects do **not** establish physical
part-block reclamation. No admin GC, bucket-wide scan, lifecycle rule, second
abort sweep, writer-quiescence claim or multi-node experiment was performed.

## Browser, TLS and clock prerequisites (separate from storage correctness)

1. Supply the intended frontend origin and a browser-reachable Garage HTTPS
   hostname with a valid certificate. Sign that exact authority/path; do not
   rewrite a presigned hostname after signing. Docker inspection showed 3900/3903
   published on IPv4/IPv6 interfaces; this is not proof of client routing or TLS.
2. Configure CORS separately, with approved origins, PUT and the checksum/request
   headers used by the client, plus ETag/checksum response exposure where needed.
   No CORS or VM/Docker setting was changed. The test origin is a probe, not an
   approved production origin. No browser permission/security bypass was attempted.
3. Resolve or explicitly bound signer/Garage clock skew before promising short-lived
   authorization. Garage's Date was approximately **46,500 seconds behind the JVM**.
   The ordinary public SDK presigner's future-dated URL was accepted. Consequently
   its apparent two-minute lifetime is not two minutes against Garage's clock.
   No clock was changed.

SDK 2.48.0's public S3Presigner has no signing-clock override. Normal-presigner
acceptance is a separate test. Provider expiry experiments re-sign SDK-marshalled
requests with the SDK S3 SigV4 signer using a **test-only** clock derived from Garage
Date, including a deliberately expired timestamp. This is a characterization aid,
not a production recommendation to trust unauthenticated HTTP Date.

The native curl TLS probe failed locally with Schannel SEC_E_NO_CREDENTIALS;
it establishes no server TLS conclusion. The Java HTTPS probe records its own
result. There is no verified production HTTPS endpoint or connected browser here.

## Scope and recovery discipline

[Capability tests](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartCapabilityTest.java)
use [journalled allocations](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartFixture.java)
and a [response-drop proxy](../backend/src/test/java/com/vaultdrive/storage/MultipartCompletionDropProxy.java).
Journal entries are forced to disk before Create/Complete, and immediately after
receiving upload IDs. New journals also pin the original endpoint/region/bucket;
reviewed disposal rejects a different current binding or an unbound journal.
The already-reviewed earlier runs predate that tightened guard and cannot be
automatically replayed by it. They retain exact disposable identities and sanitized HTTP
status/code, part metadata and byte hashes. No signed URLs, credentials, full error
bodies or permanent secrets enter evidence. Journals remain ignored under
`backend/target/garage-multipart/<UUID>/evidence.tsv`.

Cleanup aborts only known open test uploads with no dispatched completer and
deletes only confirmed completed test objects. Gated/parallel tasks are closed or
joined first. Unknown I/O retains the affected allocation and its journal instead
of claiming cleanup; unmapped uncertainty retains the whole run. Active-request
accounting prevents cleanup while an executor task is still running. Postflight
absence is observation, never cleanup authority. An unrelated
disposable control is read after every experiment and before final disposal.

The first expectation-correction run retained 17 allocations conservatively.
After review confirmed all request outcomes, the explicit
[reviewed-journal test](../backend/src/test/java/com/vaultdrive/storage/GarageMultipartJournalRecoveryTest.java)
disposed only that exact run. It rejects unknown create/part/complete/abort outcomes,
unmatched completion acknowledgements and unfinished gates. It never discovers
old runs automatically or scans a bucket. A later large-body replay after an
acknowledged abort ended in IOException before an S3 response was available. This
is TRANSPORT_UNKNOWN, not a 404 or termination proof. The exact allocation remains
retained. Explicit review disposed only the other 29 known allocations from that
run, with **no storage call at all for the uncertain target**. The reviewed run is
`b8b67b7a-08d2-4942-8242-4396a65bb1da`; its retained journal identifies the exact
key/upload ID. Partial disposal requires an explicitly specified retained key and
matching earlier abort evidence. Raw journals remain local. No physical-reclamation
or absence claim is made for that one retained allocation. Other task allocations
reached known cleanup postflight; subsequent full runs had zero newly retained
allocations.

## Reproduction and executed verification

From `backend/`, in a dedicated PowerShell session:

```powershell
. .\.env.local.ps1
# First inspect the running image/binary read-only; this is an attestation, not detection.
$env:GARAGE_MULTIPART_VERSION = '2.3.0'
$env:GARAGE_MULTIPART_TEST = 'true'
.\mvnw.cmd -o '-Dtest=GarageMultipartCapabilityTest' test
```

This opt-in is test-only and changes no existing autonomous processing flag.
The harness deliberately supports the current HTTP development endpoint for its
raw gated/proxy experiments; it is not a new general storage framework.

Initial runs corrected assumed AWS error names to Garage's observed names and
recorded acceptance of part zero. A subsequent strict different-byte same-part
assertion found the genuine intermittent content/ETag discrepancy above. It was
preserved as a failed capability and narrowed protocol requirement, not dismissed
as a passing safety property. Compilation initially found the absent public SDK
clock-override method; the test-only signer approach replaced that assumption.

Executed verification:

- Final focused capability suite: **18 passed**, zero failures/errors/skips.
- Follow-up binding-journal/compile smoke check: **1 passed** after adding the
  original-binding journal guard.
- Final tampering/response-loss checks: **2 passed** after preventing assertion
  failures from rendering signed URLs or full upstream error bodies.
- Bounded regression selection: **295 passed**, zero failures/errors/skips,
  including all twenty B4A single-PUT cases.
- Explicit reviewed-run disposal: **two one-test runs passed**, including partial
  disposal that retained the uncertain replay target.
- All new tests compiled with the repository Java/SDK dependencies. Production
  source, migrations, dependency files and processing flags have no diff.

The bounded regression
selection includes existing unit/configuration/protocol tests plus B4A's 20-case
SinglePutDispatchIntegrationTest (fresh private schema and loopback receiver).
Other Spring/database, repository, inherited HTTP and async E2E/process classes
are excluded to avoid existing fixed/shared schemas and unrelated Garage/broker
mutations. This is **not** a fresh full backend, broker or process-loss verification.
No public or retained regression schema was repaired or modified.

The exact bounded regression selection (same environment loading as above) is:

```powershell
$tests = @(
  'AuthControllerTest','AuthenticationServiceTest','FileMetadataServiceTest',
  'FileNameExtensionResolverTest','FileServiceTest','FolderAccessValidatorTest',
  'FolderNameValidatorTest','FolderServiceTest','JobWorkerConfigurationTest',
  'JwtServiceTest','KnownSuccessCleanupTest','MissingJobReconciliationConfigurationTest',
  'OutboxPublisherConfigurationTest','OutboxPublisherTest','OutboxRabbitIoTest',
  'PasswordConfigTest','ProcessRecoveryProtocolTest','PurgeConsumerConfigurationTest',
  'PurgeNotificationListenerTest','PurgeNotificationTest','RabbitOutboxTransportTest',
  'RegistrationServiceTest','S3DeletionTimeoutTest','S3ObjectStorageServiceTest',
  'SinglePutDispatchIntegrationTest','UploadFinalizationRecoveryTest'
) -join ','
.\mvnw.cmd -o "-Dtest=$tests" test
```

Existing autonomous flags must remain disabled; this command does not change
them. This selection avoids all default/fixed-schema contexts other than B4A,
which creates and disposes its own random schema. It does not mutate shared
`public` or retained regression schemas.

## GO / NO-GO and next slice

**GO for M1 only as the next bounded implementation:** session/allocation identity,
idempotency and database decision guards, with multipart excluded from V25/V26.
M0 establishes a viable **conditional** M2–M3 storage protocol: immutable signed
SHA-256/length per part, server-only completion/abort, one non-reassignable completion
dispatcher, durable receipts and retained unknown outcomes. It does not authorize
starting M1 in this task.

**NO-GO for arbitrary concurrent replacement, blind completion retries, automatic
uncertain cleanup, or enabling browser uploads now.** The smallest adjustment is
to forbid changing a registered part digest/size; changed content uses a new
allocation. Keep cancellation/expiry as durable authorization decisions, not
writer fences. Retain pending-purge blockers until a separately accepted proof
extension exists. Browser release additionally needs verified real-origin fetch,
TLS/CORS and bounded clock behavior; no new coordination infrastructure is needed.
