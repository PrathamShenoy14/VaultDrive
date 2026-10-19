# Trusted single-PUT dispatch verification (Phase 2E.5B4A)

Consolidation update: [the backend audit](backend-consolidation.md) retires synchronous upload admission/PUT and foreground receipt creation. New uploads use direct multipart only. Earlier flow descriptions, commands and verification totals below are historical milestone evidence; retained historical readers/receipts/expiry/cleanup remain supported where stated in the audit.

Follow-up: [restricted B4B cleanup](known-success-upload-cleanup.md) now relies on
this verified contract. The B4A scope and execution record below remain historical.

The [original B4 audit](known-success-cleanup-design-audit.md) is preserved verbatim.
B4A verifies its application dispatch premise; it does not implement or authorize
Garage cleanup. Production code, migrations, storage configuration and autonomous
defaults are unchanged.

## Production path and scope of the guarantee

The production-source search finds one `putObject` call, in
[S3ObjectStorageService.upload](../backend/src/main/java/com/vaultdrive/storage/S3ObjectStorageService.java).
Its only production caller is
[FileService.uploadFile](../backend/src/main/java/com/vaultdrive/file/FileService.java).
There is no overwrite/resume endpoint, presigned PUT, S3 multipart operation,
background upload continuation, PUT loop or application retry advice.

1. Each HTTP upload generates a fresh UUID and `users/{owner}/files/{file}` key.
   Metadata and the fully bound allocation commit together. Name conflicts can
   reject a repeated request before allocation/PUT; a retry after FAILED reserves
   a different UUID/key. Retained allocation constraints prohibit intentional reuse.
2. Every adapter upload calls
   [beginWrite](../backend/src/main/java/com/vaultdrive/storage/FileStorageIdentityService.java)
   in a short transaction. Shared owner hierarchy -> fresh eligible UPLOADING
   file-row lock -> conditional allocation update consumes `write_started=false`
   once and captures the admission version/folder. Commit precedes storage I/O.
3. The adapter then executes one synchronous PUT. A second adapter invocation
   must repeat admission and loses before reaching HTTP. Admission alone does
   not count physical requests: this guarantee also depends on the single call
   site, client configuration and absence of independent replay.
4. [S3StorageConfig](../backend/src/main/java/com/vaultdrive/storage/config/S3StorageConfig.java)
   builds the real AWS SDK 2.48.0 synchronous S3 client and Apache transport, with
   `StandardRetryStrategy.maxAttempts(1)`, two-second connection/acquisition limits
   and a five-second socket timeout. B4A tests do not shorten or override these.
   Cross-region access is not enabled. The installed Apache adapter disables
   automatic retries and redirects; the configured client's effective retry
   ceiling and cross-region setting are asserted as well as its observed behavior.
5. After PUT returns, `FileService` closes the multipart input stream. Only after
   successful closure does completion invoke the independent V25 receipt
   transaction, followed by READY recovery. Tests independently observe receipt
   absence at close and committed receipt visibility before READY. PUT/close
   failure creates no receipt. Receipt uncertainty neither repeats PUT nor deletes.

The trusted single-dispatch premise holds for this tested application/SDK/transport
combination. Database admission is not a storage-enforced one-use capability, and
the receipt cannot certify writers outside this protocol. Preserve these checks
when changing SDK versions, HTTP clients, retry configuration or upload paths.

## Controlled receiver and evidence

`SinglePutDispatchIntegrationTest` (historical source retired by consolidation)
uses the production Spring services, adapter, SDK factory and transport. All
storage requests go to a raw loopback HTTP/1.1
`controlled receiver` (historical source retired by consolidation)
using synthetic credentials. A second independently listening receiver is the
redirect destination. Neither receiver implements DELETE or contacts Garage.

The counter advances after the receiver parses a complete HTTP request line and
headers. Body completion and simulated accepted effects are separate evidence.
Every observed request must be PUT; even an unexpected HEAD fails the assertions.
The receiver consumes HTTP framing, supports Expect: 100-continue, closes each
connection, and remains listening through SDK return plus a bounded 300 ms extra-
arrival observation. Synchronous retries must occur before SDK return. This is
not an infinite observation or a claim about arbitrary asynchronous intermediaries.
Authentication headers are neither retained in evidence nor logged.

Each test uses committed disposable owners/allocations in a random private schema
of `vaultdrive_test`, removed after the class finishes. All four autonomous flags
are asserted false. The concurrent test starts two real adapter calls together,
holds the winning response at the receiver, and requires the losing admission to
return before releasing the winner. It asserts one received request, one consumed
allocation and no receipt: adapter return alone does not record foreground success.

| Scenario | Received PUTs | Complete bodies | Simulated accepted effects | Committed receipts |
|---|---:|---:|---:|---:|
| Successful PUT; subsequent same-key upload rejected | 1 | 1 | 1 | 1 |
| HTTP 500 | 1 | 1 | 0 | 0 |
| HTTP 503 | 1 | 1 | 0 | 0 |
| Connection reset after headers and body prefix | 1 | 0 | 0 | 0 |
| Socket read timeout after acceptance | 1 | 1 | 1 | 0 |
| HTTP 301 | 1 | 1 | 0 | 0 |
| HTTP 302 | 1 | 1 | 0 | 0 |
| HTTP 307 | 1 | 1 | 0 | 0 |
| HTTP 308 | 1 | 1 | 0 | 0 |
| Full PUT accepted; response dropped | 1 | 1 | 1 | 0 |
| Two concurrent attempts for one allocation | 1 | 1 | 1 | 0 |
| Identical HTTP upload retried after failure | 2, one per distinct key | 2 | 1 | 1 |
| Identical successful HTTP upload repeated | 1; second request returns 409 | 1 | 1 | 1 |
| Successful PUT and close, then receipt/READY | 1 | 1 | 1 | 1 |
| Successful PUT, failed stream close | 1 | 1 | 1 | 0 |
| Receipt transaction rolls back | 1 | 1 | 1 | 0 |
| Receipt persistence and authoritative reload unavailable | 1 | 1 | 1 | 0 |
| Receipt commits; acknowledgement lost; independent reload | 1 | 1 | 1 | 1 |
| Unallocated key | 0 | 0 | 0 | 0 |
| Effective client configuration check | 0 | 0 | 0 | 0 |

Redirect destination requests are **zero in every scenario**, including redirects
carrying both Location and a different `x-amz-bucket-region`. Error status codes,
socket-timeout root cause and transport-I/O failures are checked, so a different
failure cannot silently stand in for the intended fault. Per-scenario sanitized
`PUT_DISPATCH` lines accompany the assertions. These are HTTP receiver counts,
not Mockito SDK invocation counts.

Receipt rollback/unavailability/acknowledgement loss are controlled boundary
injections, not database-network partitions or JVM kills. The rollback injection
executes the real receipt write and throws inside its transaction. Lost
acknowledgement throws outside the real committed transaction, then invokes the
production completion logic with a delegating boundary and independent reload.

## Safety conclusion and remaining assumptions

**B4's restricted design remains GO under the existing trusted-writer contract.**
This evidence verifies the application single-dispatch premise; cleanup still
requires separately accepted irreversible abandonment, immutable intent/target,
recoverable claims, outcome receipts, READY exclusion and purge coordination.
No cleanup implementation or enablement follows from B4A.

In particular, read timeout, reset and dropped response are not evidence that
Garage received no PUT or could not finish one. The controlled timeout and dropped-
response scenarios deliberately retain accepted effects while PostgreSQL has no
success receipt. Such real admitted uncertain writers remain blocked indefinitely
under the current recovery contract. Successful PUT with failed close and success
before an uncommitted receipt remain conservative gaps.

The receiver is not Garage: it does not establish Garage durability, replication,
generation-specific deletion or multi-node behavior. No proxy/load balancer is
interposed. An intermediary may replay independently; external writers can bypass
admission; leaked credentials, namespace/endpoint reassignment, older incompatible
instances and future retry/upload changes can invalidate the premise. TCP packet
retransmission within one HTTP exchange is different from another HTTP PUT.
Neither database leases nor these finite tests fence those independent writers.
If that operational boundary cannot be maintained, automatic cleanup is NO-GO.

The first broad regression run exposed an unrelated fixture assumption: the JVM
was 7,796 seconds ahead of PostgreSQL when independently checked. Existing outbox
publisher tests created supposedly due events using JVM time minus one minute,
then correctly found nothing claimable against PostgreSQL time. Only their `event()`
fixture now uses PostgreSQL time minus one minute, matching the eligibility clock.
All eighteen publisher cases passed afterward. No production timestamp behavior
or infrastructure clock was changed. This observed skew can still delay existing
production publication of application-dated events; operational clock alignment
and any broader production change remain outside B4A.

## Reproduce and executed verification

From `backend/`, load the ignored local environment in the same PowerShell process:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=SinglePutDispatchIntegrationTest,FileServiceTest,UploadFinalizationRecoveryTest,UploadFinalizationRecoveryIntegrationTest,StaleUploadExpiryIntegrationTest' test
```

The B4A class overrides only test endpoint/binding/credentials and uses its own
random database schema. It sends no Garage requests and needs no S3 credentials.
PostgreSQL and normal test JWT configuration are still required.

The safely runnable regression command excludes the seven classes that mutate
Garage, keeps autonomous end-to-end/process harnesses disabled, and leaves live
broker tests without their opt-in. The default test S3 endpoint is deliberately
unreachable loopback with synthetic credentials; B4A supplies its own receiver.
Default database contexts also receive a new private regression schema; tests
with existing class-specific isolation retain that isolation.

```powershell
$env:S3_ENDPOINT = 'http://127.0.0.1:1'
$env:S3_ACCESS_KEY = 'fixture-access'
$env:S3_SECRET_KEY = 'fixture-secret'
$env:ASYNC_E2E_TEST = 'false'
$env:ASYNC_PROCESS_TEST = 'false'
$env:OUTBOX_PUBLISHER_ENABLED = 'false'
$env:PURGE_CONSUMER_ENABLED = 'false'
$env:JOB_WORKER_ENABLED = 'false'
$env:MISSING_JOB_RECONCILIATION_ENABLED = 'false'
Remove-Item Env:RABBITMQ_TEST_VHOST -ErrorAction SilentlyContinue
$schema = 'b4a_regression_' + [guid]::NewGuid().ToString('N')
$safeTests = '*Test,!S3StorageIntegrationTest,!UploadSuccessGarageIntegrationTest,!FilePurgeIntegrationTest,!FolderPurgeFileIntegrationTest,!FolderPurgeMetadataIntegrationTest,!MissingJobReconciliationIntegrationTest,!HierarchyHttpTransactionTest'
.\mvnw.cmd "-Dtest=$safeTests" `
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$schema" `
  "-Dspring.flyway.schemas=$schema" "-Dspring.flyway.default-schema=$schema" test
```

Run in a dedicated shell after loading the local environment. Retain the exact
generated schema identity and verify its test-owned evidence before disposing of
that schema. Never sweep pre-existing schemas or infer Garage object absence from
these tests. The exclusions are a deliberate no-Garage-mutation boundary, not
fresh live Garage, broker or process-loss verification.

Executed on 2026-10-17 with AWS SDK 2.48.0, the production Apache HTTP client and
PostgreSQL 17.11:

- Final focused upload/admission/receipt run: **149 passed, zero failures/errors/skips**,
  including all twenty B4A cases. All HTTP counts in the table above passed.
- Corrected outbox fixture check: **18 passed, zero failures/errors/skips**.
- Final safely runnable regression suite: **677 tests, 668 passed, zero failures/errors,
  nine intentional skips**. The B4A class passed all twenty cases again.
- Seven Garage-mutating classes were excluded explicitly: `S3StorageIntegrationTest`,
  `UploadSuccessGarageIntegrationTest`, `FilePurgeIntegrationTest`,
  `FolderPurgeFileIntegrationTest`, `FolderPurgeMetadataIntegrationTest`,
  `MissingJobReconciliationIntegrationTest`, and `HierarchyHttpTransactionTest`.
  The nine skips were seven live broker tests and the end-to-end and process-loss
  harnesses. No new live Garage, broker, abrupt-crash or cleanup evidence is claimed.
- The initial broad run had eight assertion failures and six errors, all in the
  existing outbox publisher class due to the independently confirmed clock skew.
  Its fixture correction and the subsequent complete safe regression run passed.
  Production code, schema migrations, VM clocks and normal processing defaults
  were unchanged.
- Read-only PostgreSQL inspection confirmed all four B4A `single_put_` schemas
  from these runs absent, along with the exact receipt/expiry schemas inspected.
  Two task-created default regression schemas remain retained:
  `b4a_regression_104938ecd8a74326803fb3c31ed05ed5` and
  `b4a_regression_95367c88d4f54c3f833efdd1b13890d1`. Each is owned by the test role
  and contains three never-admitted allocations, zero admitted writes/receipts,
  and no users, folders, files, outbox events or jobs. Automatic approval review
  rejected explicit schema disposal because destructive database cleanup lacked
  separate approval; exact-target approval was requested and no disposal executed.
- Documentation links, whitespace and private environment-value exclusions passed.
  Only synthetic credentials occur in test sources. New B4A tests contain no
  storage DELETE call. No development data, pre-existing Garage objects,
  credentials, infrastructure configuration or processing flags were changed.

This completes B4A verification only. B4 cleanup remains unimplemented and requires
a separately accepted implementation scope with the original audit's safety gates.
