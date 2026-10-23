# Real-browser multipart verification — 2026-10-23

**Result: blocked; zero confirmed real-browser passes.** This is the safe fallback
configuration plan and execution procedure, not completed browser release proof.
The [main runbook](browser-multipart-interoperability.md) defines the full scenario
contract. No infrastructure change was made and no retained ambiguous allocation
was inspected for mutation, adopted, aborted or swept.

## Observed environment

Read-only SSH used the configured `vaultdrive-vm` alias. VM/network/time inspection
was performed at approximately 17:06–17:10 UTC; browser availability was also
rechecked during this task:

| Item | Observed result |
|---|---|
| Browser inventory | Computer-use tool returned `apps: [], browsers: []`; attempting to open `https://localhost:9443/` returned `Browser is not available: iab`. No engine/version, page load, browser origin, browser network capture or browser certificate observation exists. |
| Windows runtime | Node 20.11.0; backend tests use Java 21.0.8. No API listener on 8080 or harness on 9443 at inspection. |
| Local storage/database access | Existing loopback listeners on 3900 and 5432; no local 5672 listener before the task-owned regression tunnel. |
| Configured storage | Private environment supplies no S3 endpoint/bucket override: application defaults `http://localhost:3900`, `vaultdrive-files`, region `garage` apply. |
| Garage | Running image `dxflrs/garage:v2.3.0`; `/garage --version` also reports 2.3.0. S3 configuration: bind `[::]:3900`, region `garage`, root domain `.s3.garage.localhost`. |
| VM listeners | SSH 22, PostgreSQL 5432, Garage 3900/3903, loopback AMQP 5672 and management 15672; no 443/9443/9444 listener. No TLS ingress container appears in `docker ps`. This does not inventory every external network edge. |
| Page TLS | Neither `TLS_CERT` nor `TLS_KEY` configured in this shell. No certificate store/private-key search, trust installation or warning bypass performed. No usable trusted certificate was established by this inspection. |
| Storage CORS diagnostic | VM-side OPTIONS to `http://127.0.0.1:3900/vaultdrive-files`, Origin `https://localhost:9443`, requested PUT, requested header `x-amz-checksum-sha256`: **403**, `Access-Control-Allow-Origin: *`; no Allow-Methods, Allow-Headers or Expose-Headers. |
| Current bucket policy | Not retrieved through authenticated GetBucketCors in this recheck. The denied OPTIONS proves the requested preflight is unusable; it does not prove which policy is stored. |

The OPTIONS result is a real HTTP diagnostic, **not a browser-origin request**.
The page/storage origins above are proposed/configured origins, not observed
browser execution. No signed URL was exported or rewritten. HTTPS on the
configured HTTP endpoint was not presented as working TLS.

Clock sample bracket: Windows UTC **17:07:43.9515715Z** before SSH and
**17:07:45.8269382Z** afterward; VM **17:07:45Z**; Garage HTTP Date
**17:07:45 GMT**; PostgreSQL `clock_timestamp()` **17:07:45.706826Z**.
All lie in that approximately 1.88-second bracket, allowing for Date's one-second
precision. VM reports `NTP=yes`, `NTPSynchronized=yes`. Windows reports no leap
warning, stratum 5, last successful sync 21:46:38 local (16:16:38Z), root
dispersion approximately 4.18 seconds. These are samples, not an asserted
subsecond or ongoing synchronization guarantee. The focused backend JVM emitted
UTC logs in the same time window; no production signer clock instrumentation or
browser clock sample was collected.

## Verification matrix

“Node” below means synthetic fetch/protocol checks or loopback HTTP proxy tests.
“Backend” means actual PostgreSQL/security/Garage tests, not browser execution.

| Requirement / scenario | Real-browser result | Available evidence / remaining gate |
|---|---|---|
| Trusted HTTPS page and storage, hostname/chain/expiry, no mixed content | BLOCKED | HTTP storage configured; no page TLS material configured or connected browser. |
| API reserve/initiate/sign/list/complete/recover/cancel CORS | NOT EXECUTED | Same-origin `/api/v1/` design: no cross-origin API preflight is expected. Storage Create/Complete/Abort/ListParts are backend SDK calls. Inspect actual browser routing later. |
| Storage PUT preflight and actual PUT | BLOCKED | Nonbrowser OPTIONS denied; no valid HTTPS storage origin. |
| Origin/method/header restrictions and denial paths | NOT EXECUTED | Exact-origin PUT/checksum template only; Node Host/Origin/CSP tests pass. Actual Garage policy readback/positive and negative browser preflights pending. |
| Login, secure cookie acceptance, refresh/CSRF/logout | NOT EXECUTED | Node serialization/header preservation and real backend security tests; browser cookie behavior pending. |
| Nonempty upload, 8 MiB + 8 MiB + 37-byte fixture, READY | NOT EXECUTED | Backend multipart/live Garage checks pass independently. UI glue and real forbidden-header/framing behavior unverified. |
| Exposed ETag/checksum and exact part evidence | NOT EXECUTED | Harness now checks exact backend ListParts ETag, digest and size; Node mismatch cases pass. Browser header exposure pending. |
| Explicit identical retry, URL refresh, multi-part pagination | NOT EXECUTED | Protocol/live Garage coverage; new replay button unexecuted in browser. |
| Interruption, reload, refresh and resume | NOT EXECUTED | Authoritative-list logic tested; real cancellation/in-flight and reload behavior pending. |
| Cancellation of RESERVED and OPEN sessions | NOT EXECUTED | Backend cancellation and lost-response guards tested. No browser Abort/Complete fault was injected. |
| Native expired URL and invalid capability/body errors | NOT EXECUTED | Node synthetic statuses/body probes; prior Garage M0 observed expiry 400. Current native browser expiry still pending. |
| Completion loss, explicit recovery, one dispatch | NOT EXECUTED | Live backend response-drop recovery tests; no browser fault-injection execution. |
| Authenticated exact-byte download | NOT EXECUTED | Supported API streams through Spring Boot. No presigned GET exists, so Garage GET CORS and signed-download validity are N/A for this contract. |
| Upload bytes bypass Spring Boot | NOT EXECUTED | Source/Node checks target signed storage URLs with omitted credentials; actual Network request destinations/sizes pending. Backend downloads intentionally carry bytes. |
| Browser/JVM/Garage/PostgreSQL clock consistency | PARTIAL DIAGNOSTIC | Aligned nonbrowser samples above; browser sample, sustained skew monitoring and browser native-expiry bound absent. |
| Editor/owner download and revocation | NOT EXECUTED | Backend delegated authorization/races; two-profile browser procedure pending. |
| Immutable 24-hour session expiry and logical expiry processing | NOT EXECUTED | Existing backend guards only; no 24-hour browser run or production timestamp/TTL modification. |
| HTTP/2 / browser portability | UNVERIFIED | No browser engine/version or negotiated protocol evidence. Node harness uses HTTP/1.1 TLS; any future storage HTTP/2 path must be recorded separately. |

Browser response ETags are compared with the backend's authoritative ListParts;
the backend does not accept a client completion manifest. It independently freezes
registered lengths/digests and provider ETags. No production endpoint, schema,
identity, decision, lock order, dispatch, recovery or cleanup rule changed.

## Configuration plan requiring explicit approval

This section is a reviewable plan only. **Do not run the infrastructure mutation
commands until the user approves the concrete endpoint, certificate and merged
policy.** The current task authorizes inspection and test code, not these changes.

1. Use a dedicated browser profile and an isolated backend/database schema with
   fresh accounts/sessions. Keep all autonomous processors/probes disabled. Keep
   historical storage bindings untouched; never start another writer against them.
2. Proposed local-only topology: page/API `https://localhost:9443`, storage
   `https://localhost:9444`, backend `http://127.0.0.1:8080`. Reuse the existing
   authorized loopback storage forwarding to `http://127.0.0.1:3900`. An approved
   TLS ingress on **127.0.0.1:9444** must forward to 3900 preserving original Host
   (`localhost:9444`), method, exact path/query, checksum header, body framing and
   response headers. Disable automatic upstream retries and redirects; redact
   query strings and bodies. No VM firewall/DNS/public bind is needed for this
   proposed local topology. Creating that listener is itself approval-gated.
3. Provide already trusted certificates valid for `localhost` on both local TLS
   listeners, with an approved trust chain and unexpired dates. Keep private keys
   outside the repository. If certificates/trust must be created or changed,
   obtain explicit approval first. Browser trust and JVM trust must both succeed;
   never disable validation or use browser security flags. Node's trust store is
   independent too if `API_UPSTREAM` is HTTPS.
4. Configure **only the isolated new backend** with S3 endpoint
   `https://localhost:9444`; retain region/bucket/namespace settings. Use only fresh
   allocations with that immutable binding. A returned signed URL must already
   contain this exact authority, including port. Do not rewrite URLs or retained
   allocations. If a different approved endpoint is chosen, replace the plan's
   endpoint consistently before running anything.
5. Using an authorized administrative S3 client, read/back up current bucket CORS,
   then review a merged policy containing the existing
   [template](../tools/browser-multipart/garage-cors.example.json). This exact page
   origin requires PUT and `x-amz-checksum-sha256`, exposing ETag/checksum. Do not
   add GET, POST, DELETE, Authorization, cookies, wildcards, Host, Content-Length or
   OPTIONS to the template. Denied-origin error responses must not be interpreted
   as working CORS merely because Garage emits wildcard error headers.

Example administrative commands below require an **already installed/approved AWS
CLI and private operator credential profile**; neither was established here. They
are executable operator instructions after approval, not commands run in this task.
`$corsBackup` and `$corsMerged` must point outside the repository. Never overwrite
an existing backup. A 404 NoSuchCORSConfiguration permits a new policy; a 403 or
transport error does not establish absence. Merge manually after reviewing the
backup; PutBucketCors replaces the complete policy.

```powershell
$storageEndpoint='https://localhost:9444'
$bucket='vaultdrive-files'
$corsBackup='C:\private\vaultdrive-cors-before.json'
$corsMerged='C:\private\vaultdrive-cors-reviewed.json'
if (Test-Path -LiteralPath $corsBackup) { throw 'Choose a new backup path' }
aws --profile vaultdrive-operator --endpoint-url $storageEndpoint s3api get-bucket-cors --bucket $bucket --output json > $corsBackup
# STOP on failure except explicitly confirmed NoSuchCORSConfiguration.
# Review the complete merged policy at $corsMerged before the approved mutation.
aws --profile vaultdrive-operator --endpoint-url $storageEndpoint s3api put-bucket-cors --bucket $bucket --cors-configuration "file://$corsMerged"
if ($LASTEXITCODE -ne 0) { throw 'CORS update unconfirmed; inspect before retry' }
aws --profile vaultdrive-operator --endpoint-url $storageEndpoint s3api get-bucket-cors --bucket $bucket --output json
```

Do not automatically restore the backup at test end: restoration is another
policy mutation that could overwrite concurrent approved changes. Review it with
the operator. Do not mutate retained allocations or infer cleanup authority from
a successful test, cancellation, age or provider absence.

## Executable continuation after prerequisites are approved

First, run these read-only diagnostic commands from the repository root. Curl
must validate TLS; do not add `-k`. They do not establish browser enforcement.

```powershell
ssh -o BatchMode=yes vaultdrive-vm "date -u; timedatectl show -p NTPSynchronized -p NTP; docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'; ss -lnt"
w32tm /query /status
$storageEndpoint='https://localhost:9444'
curl.exe --fail-with-body --max-time 10 -sS -D - -o NUL -X OPTIONS "$storageEndpoint/vaultdrive-files" -H 'Origin: https://localhost:9443' -H 'Access-Control-Request-Method: PUT' -H 'Access-Control-Request-Headers: x-amz-checksum-sha256'
# Deliberate policy denials: inspect headers as well as status.
curl.exe --max-time 10 -sS -D - -o NUL -X OPTIONS "$storageEndpoint/vaultdrive-files" -H 'Origin: https://unapproved.invalid' -H 'Access-Control-Request-Method: PUT' -H 'Access-Control-Request-Headers: x-amz-checksum-sha256'
curl.exe --max-time 10 -sS -D - -o NUL -X OPTIONS "$storageEndpoint/vaultdrive-files" -H 'Origin: https://localhost:9443' -H 'Access-Control-Request-Method: DELETE'
curl.exe --max-time 10 -sS -D - -o NUL -X OPTIONS "$storageEndpoint/vaultdrive-files" -H 'Origin: https://localhost:9443' -H 'Access-Control-Request-Method: PUT' -H 'Access-Control-Request-Headers: authorization'
```

Readback/positive preflight must authorize the exact page origin/method/header.
Unapproved origins/methods/headers must not gain browser access. Require the exact
approved page origin, without wildcard authorization. Expose-Headers must be checked
on the **actual PUT response**; it need not appear on OPTIONS. For real-browser
denial, separately approve a second local harness listener such as
`https://localhost:9445`, verify that origin is absent from the complete bucket
policy, then run the same server with `HARNESS_ORIGIN=https://localhost:9445` in a
separate shell/profile. Use a fresh disposable session to attempt its signed PUT:
JavaScript must fail CORS and Network must show the denied preflight. The synthetic
`unapproved.invalid` curl Origin above does not require DNS and is not a hosted
browser page. Do not send a real bearer token to Garage.
Remember that CORS limits script access, not all cross-origin request dispatch.

In a dedicated PowerShell window, start the isolated backend (this creates only
the explicitly named new verification schema):

```powershell
cd backend
. .\.env.local.ps1
$env:S3_ENDPOINT='https://localhost:9444'
$env:OUTBOX_PUBLISHER_ENABLED='false'
$env:PURGE_CONSUMER_ENABLED='false'
$env:SHARING_NOTIFICATIONS_CONSUMER_ENABLED='false'
$env:JOB_WORKER_ENABLED='false'
$env:MISSING_JOB_RECONCILIATION_ENABLED='false'
$env:PURGE_FINALIZATION_ENABLED='false'
$env:MULTIPART_EXPIRY_ENABLED='false'
$env:OPERATIONS_PROBES_ENABLED='false'
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080 --spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=browser_manual_fresh --spring.flyway.schemas=browser_manual_fresh --spring.flyway.default-schema=browser_manual_fresh'
```

Use a new schema name for each independent run; do not use the retained test
schemas or activate `deployment`, which requires processors to be enabled.
Provision disposable users through the existing registration API, following its
contract; keep credentials private. Then in another root PowerShell window:

```powershell
$env:TLS_CERT='C:\private\localhost-cert.pem'
$env:TLS_KEY='C:\private\localhost-key.pem'
$env:HARNESS_ORIGIN='https://localhost:9443'
$env:API_UPSTREAM='http://127.0.0.1:8080'
$env:GARAGE_ORIGIN='https://localhost:9444'
node tools/browser-multipart/serve.mjs
```

Open `https://localhost:9443/` in the dedicated real browser profile. Record exact
browser product/version and engine, UTC start/end, page/storage origins, negotiated
protocols, certificate trust/hostname/expiry, and each scenario's outcome. If the
browser cannot connect to this workstation's loopback, stop: do not expose ports
or change DNS without approval.

1. Run authentication/negative CSRF/refresh/profile checks. Privately inspect
   Secure, HttpOnly, Strict and host-only cookie attributes. Confirm no backend
   CORS relaxation was necessary.
2. Generate the three-part fixture; reserve and initiate exactly once. Upload
   first missing part. In Network, retain a sanitized transcription of OPTIONS
   and PUT method, origin/authority, status, requested/allowed header names,
   exposed header names, part number/byte count and protocol. Exclude paths/queries,
   cookies, auth, signed URLs and provider bodies from committed evidence.
3. Replay identical first part twice; both responses/evidence must match. Reload,
   refresh once, regenerate the exact fixture and list/resume missing parts.
   For an actual in-flight interruption, use DevTools Offline during a part PUT,
   restore connectivity, and read/list before resuming. Record uncertainty and
   actual observed state; interruption alone proves neither rejection nor absence.
4. On a disposable OPEN session, run negative probes. Each must return a readable
   400/403, never a CORS TypeError counted as rejection. Record the actual provider
   code privately and only its sanitized classification in the evidence matrix.
5. Refresh first part URL and validate retained URL before expiry. Record only
   signing timestamp/deadline and client UTC, not the URL. Keep this session OPEN,
   wait beyond deadline plus an independently measured skew/precision margin
   (at least five seconds), then try retained URL. Confirm an expiry-specific
   error privately. Refresh and validate a fresh URL successfully.
6. Complete the main fully uploaded session once; require READY, then Download
   and compare exact size/SHA-256. Check that all upload byte bodies went only to
   Garage, all API uploads were bounded JSON/control requests, and downloaded bytes
   came through the authenticated backend attachment route. No signed GET is used.
7. Use separate sessions for RESERVED/OPEN cancellation, completion recovery,
   Editor/revocation and immutable 24-hour expiry, following the main runbook.
   A browser Offline toggle is not deterministic proof of a lost Complete/Abort
   response. If no approved deterministic transport fault setup is available,
   mark that browser recovery case unverified and retain the separate backend
   response-drop evidence. Never redispatch an uncertain Create/Complete/Abort.
8. Export sanitized results; review before committing. Record manual Network
   observations alongside the export: export alone does not capture preflight,
   certificates, actual Host/framing, protocol or byte routing. Never commit a raw
   HAR, screenshot, environment file or credentials. Keep unexecuted cases open.

## Clock-skew interpretation and continuation checks

Before and after native-expiry experiments, bracket several SSH VM/PostgreSQL
samples with Windows UTC and record browser `new Date().toISOString()` locally.
Inspect backend health response Date via the same-origin proxy and the signature's
`X-Amz-Date` privately. Date probes have one-second granularity and network delay;
record RTT/uncertainty rather than claiming exact clock offsets. The proxy now
preserves upstream Date, so it is not silently replaced by proxy time.

Backend signing uses JVM time; session eligibility uses PostgreSQL time; Garage
decides acceptance of a fresh signed request. Browser time governs only UI checks
and displayed waits. A browser clock error can invalidate the timing experiment
without changing a signature. If Garage is behind the signer, wall-clock validity
can extend; if ahead, requests can expire earlier. Prior M0 observed future-dated
signatures accepted. No numerical future-skew rejection threshold has been
established for this deployment. Do not assume AWS's threshold applies to Garage.

Do not change system clocks, shorten production TTLs, modify signature parameters
or offset signing from HTTP Date. A deliberate skew-threshold experiment needs a
separately approved disposable environment and cannot count as native browser
expiry evidence here. An in-flight part may succeed after expiry; this neither
revokes the part nor establishes quiescence/physical cleanup authority. Repeat
time monitoring over the intended operating interval before asserting a bound.

## Repository verification for this recheck

Node: `node --test tools/browser-multipart/protocol.test.mjs tools/browser-multipart/serve.test.mjs`
passed **19 tests**, zero failures/skips. `node --check` passed for app.mjs,
protocol.mjs and serve.mjs. Initial sandbox execution failed before tests could start with `spawn
EPERM`; the approved rerun outside the restricted sandbox passed. These are
protocol/loopback tests, not a browser or trusted-TLS execution.

Focused Maven selection passed **243 tests**, zero failures/errors/skips, at
17:09:26Z. From `backend/`, load `. .\.env.local.ps1`, set all four safe
`GARAGE_MULTIPART_{TRANSFER,COMPLETION,CANCELLATION,PURGE}_TEST=true` gates plus
`GARAGE_MULTIPART_VERSION=2.3.0`, then run:

```powershell
.\mvnw.cmd -o '-Dtest=S3MultipartStorageTest,SecurityIntegrationTest,AuthControllerTest,AuthenticationSessionIntegrationTest,MultipartTransferIntegrationTest,MultipartCompletionIntegrationTest,MultipartCancellationIntegrationTest,DelegatedMultipartIntegrationTest,GarageMultipartTransferIntegrationTest,GarageMultipartCompletionIntegrationTest,GarageMultipartCompletionEvidenceTest,GarageMultipartCancellationIntegrationTest,GarageMultipartPurgeIntegrationTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=browser_real_20261023_focused' '-Dspring.flyway.schemas=browser_real_20261023_focused' '-Dspring.flyway.default-schema=browser_real_20261023_focused' test
```

For the full regression, a task-owned loopback-only SSH forward used the existing
isolated RabbitMQ vhost. No broker policy, credentials or default-vhost topology
was changed. Equivalent foreground tunnel command in a separate private window:

```powershell
ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -N -L 127.0.0.1:5672:127.0.0.1:5672 vaultdrive-vm
```

From `backend/`, with the tunnel running:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
$env:GARAGE_COPY_TEST='true'
.\mvnw.cmd -o '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=browser_real_20261023_full' '-Dspring.flyway.schemas=browser_real_20261023_full' '-Dspring.flyway.default-schema=browser_real_20261023_full' test
```

Stop only that task-owned tunnel afterward. The two named verification schemas
are retained; tests with their own schemas/fixtures retain their established
isolation and cleanup patterns. Existing migration checksums and shared data were
not repaired. Test logs and provider journals remain ignored under
`backend/target`; they are not browser artifacts.

Full regression finished **2026-10-23 17:18:15Z**: **1,579 tests, 1,558 passed,
21 skipped, zero failures/errors**. This includes all **27** enabled live Garage
multipart/evidence checks and **eight** live Garage copy/empty-object cases, plus
the isolated RabbitMQ regressions. The temporary AMQP tunnel was closed by the
owning process; a subsequent listener check found no localhost 5672 listener.

| Separately gated suite | Skipped | Requirement not enabled |
|---|---:|---|
| AsyncPurgeEndToEndTest | 1 | `ASYNC_E2E_TEST=true` and the separate continuous end-to-end runbook |
| AsyncPurgeProcessRecoveryTest | 1 | `ASYNC_PROCESS_TEST=true` and approved process-kill procedure |
| GarageMultipartCapabilityTest | 18 | `GARAGE_MULTIPART_TEST=true` and separate M0 characterization |
| GarageMultipartJournalRecoveryTest | 1 | `GARAGE_MULTIPART_REVIEWED_RUN` with an explicitly reviewed retained UUID |

JavaScript syntax, all **27** HTML button bindings, CORS JSON, local Markdown
targets, PowerShell example syntax and Git whitespace checks passed. The safe
repository/configuration fallback is verified within these bounds. The real-browser
objective is **not achieved**: browser TLS/cookies/preflight/PUT framing/expiry,
network routing, recovery, download and sustained skew requirements remain open.
