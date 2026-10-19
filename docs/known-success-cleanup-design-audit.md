**Recommendation: GO for a narrowly restricted B4 implementation under the existing trusted-writer contract. A V25 receipt alone is not deletion authority.** Cleanup must also prove permanent abandonment and preserve the original allocation. Historical or otherwise uncertain admitted writers remain NO-GO.

Consolidation update: [the backend audit](backend-consolidation.md) retires synchronous upload admission/PUT and foreground receipt creation. New uploads use direct multipart only. Earlier flow descriptions, commands and verification totals below are historical milestone evidence; retained historical readers/receipts/expiry/cleanup remain supported where stated in the audit.

Reviewed baseline: `310b1315dbe784bcba8c82bb599cf7c98a46175c`. The working tree was clean. This investigation made no changes and ran no tests that mutate databases or storage. No commit was made.

1. **The current foreground sequence is precise.**

   | Step | Confirmed behavior |
   |---|---|
   | Reservation | Generate a new file UUID/key. Commit UPLOADING metadata and its fully bound allocation together under shared owner hierarchy coordination. |
   | Stream acquisition | Open the multipart input stream after reservation. Failure here attempts FAILED without admitting a writer. |
   | Admission | `beginWrite` acquires shared hierarchy → eligible file-row lock → allocation update. It validates ancestry/binding and atomically consumes `write_started=false`, capturing admission version/folder. Admission commits before PUT. |
   | PUT | The adapter executes one synchronous `s3Client.putObject` call outside database transactions. |
   | Closure | `FileService` exits try-with-resources. Both PUT return and stream closure must succeed before completion begins. |
   | Success receipt | A separate REQUIRES_NEW transaction locks hierarchy → fresh file → allocation and records immutable PostgreSQL-generated `put_succeeded_at`. |
   | READY | Another transaction freshly validates identity, admission version/folder, lifecycle, ancestry and conflicting purge evidence, then performs the status/version CAS. |
   | Failure | Stream acquisition, admission, PUT or closure failure attempts UPLOADING → FAILED. Receipt/finalization uncertainty retains bytes and does not attempt FAILED or DELETE. |

   Current destination rejection leaves receipt-backed UPLOADING blocked. The historical FAILED-plus-best-effort-DELETE description is superseded.

   Sources: [FileService](C:/Users/ABC/Desktop/VaultDrive/backend/src/main/java/com/vaultdrive/file/FileService.java:66), [admission](C:/Users/ABC/Desktop/VaultDrive/backend/src/main/java/com/vaultdrive/storage/FileStorageIdentityService.java:50), [receipt and recovery transactions](C:/Users/ABC/Desktop/VaultDrive/backend/src/main/java/com/vaultdrive/file/UploadFinalizationTransactions.java:33).

2. **A committed receipt is not universal proof that every possible writer is quiescent.**

   PostgreSQL verifies receipt identity and immutability; it cannot verify the external PUT or discover independent writers. `recordSuccess` trusts its caller to have observed PUT return and successful closure.

   Under the **current complete application protocol**, however, the receipt supplies terminal evidence for the allocation’s sole permitted PUT:

   - Every production adapter invocation must consume admission.
   - Only one invocation can succeed.
   - That invocation contains one PUT call.
   - The configured SDK and transport do not automatically replay it.
   - Receipt recording follows its successful return.
   - Recovery never resumes or repeats PUT.

   Therefore, within the accepted trusted-writer boundary, there is no remaining application PUT continuation that can write afterward. This is an application protocol proof, not Garage-enforced fencing.

3. **Single admission alone prevents repeated admission transactions—not arbitrary repeated dispatch.**

   The database does not count PUTs or issue a storage-enforced one-use capability. Code that reused the returned target in a loop, bypassed admission through the SDK, or enabled retries could dispatch repeatedly after one admission.

   The present implementation prevents repeated dispatch **through composition of admission, control flow and client configuration**. Concurrent adapter invocations lose admission before PUT. Repeated HTTP requests generate new UUIDs/keys. There is no production overwrite, resume, presigned PUT or S3 multipart path.

   Existing competing-write tests support the adapter boundary; they do not establish every possible transport replay behavior.

4. **Retries, disconnects and crashes divide into two different evidence classes.**

   | Situation | Consequence |
   |---|---|
   | SDK retry | `StandardRetryStrategy.maxAttempts(1)` permits only the initial attempt. Installed SDK 2.48.0 inspection confirmed the retry ceiling. |
   | Apache transport retry/redirect | Installed Apache adapter bytecode disables automatic retries and redirect handling. The optional S3 cross-region replay decorator is not enabled. |
   | HTTP client disconnect | No explicit application cancellation protocol exists. Service execution may continue. A disconnect proves neither cancellation nor storage quiescence. Exact servlet behavior remains untested here. |
   | Admission committed; process crashes before PUT | No receipt. A delayed continuation cannot be distinguished from an uncertain request after restart. Blocked. |
   | PUT times out, disconnects or throws | Garage may still finish. No receipt; FAILED does not establish termination. Blocked. |
   | PUT succeeds; close fails | No receipt, despite possible complete bytes. Ordinary failure marking may commit FAILED. Blocked. |
   | PUT/close succeed; crash before receipt commit | Success is lost as durable evidence. Blocked. |
   | Receipt commits; process crashes | The current protocol’s PUT has already returned. Recovery may decide metadata fate without repeating PUT. |
   | Receipt acknowledgement is lost | Independently reload the receipt. Never compensate with DELETE merely because the transaction threw. |

   Upload connection/acquisition/socket limits are not a total upload deadline. Waiting longer cannot convert an uncertain request into terminal evidence. AWS documents the distinction between initial attempts and retries in its [retry guidance](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html).

   This proof also assumes no intermediary independently replays PUT and no writer bypasses the agreed protocol. Neither behavior is certified by the receipt.

5. **Garage cannot compensate for an incomplete writer proof.**

   Garage 2.3.0 PUT does not enforce destination create-only conditions. DELETE dispatch ignores requested generation identity and operates on the key. Returned version identifiers therefore cannot protect cleanup against replacement bytes. Adding an ETag check or HEAD-before-DELETE would leave a check/delete race. See the tagged [PUT implementation](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/put.rs) and [DELETE dispatch](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/api_server.rs).

   A successful PUT response follows completed object publication. Garage may continue replication internally; literal termination of every internal task is unnecessary. Delayed replication of the same version is distinct from another PUT creating a new version. Garage’s deletion tombstones and version merge rules handle that distinction under its normal consistency contract. This is source-based reasoning, not newly executed failover evidence. See [deletion](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/delete.rs), [table replication](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/table/table.rs) and [object merging](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/model/s3/object_table.rs).

6. **Known-success deletion is safe only when all necessary conditions hold.**

   The allocation must have genuine V25 success evidence from the audited single-dispatch protocol, exact immutable file/owner/key/full-binding identity, and permanent key nonreuse. The configured client must still match that binding.

   Before DELETE, an independently committed business decision must permanently prohibit READY adoption. Current metadata must match the decision’s expected lifecycle/version. Missing metadata, inaccessible ancestry or an old inventory classification cannot substitute for this decision.

   Existing purge targets, work, membership or conflicting receipts must defer the candidate to their owning protocol. Ordinary trashed ancestry alone is not abandonment: B3 intentionally permits restore followed by READY recovery.

   A receipt-backed FAILED allocation can satisfy permanent abandonment after fresh validation. An UPLOADING allocation requires an atomic FAILED transition plus retained cleanup intent before storage I/O.

7. **The principal races require permanent decisions, not repeated eligibility checks.**

   | Race | Required result |
   |---|---|
   | Cleanup versus foreground/B3 READY | READY first means no cleanup intent or DELETE. Cleanup’s atomic FAILED/intent decision first means READY loses permanently. |
   | Cleanup versus ordinary FAILED | Preserve one matching terminal state and one immutable intent. Never infer PUT success from FAILED. |
   | Folder trash/restore | Shared cleanup coordination serializes with exclusive folder mutations. Restore before abandonment can restore recovery eligibility; restore afterward must not resurrect an abandoned file. |
   | Folder purge/discovery | Purge first defers cleanup without lifecycle/version mutation. Cleanup first must finish its metadata decision before discovery snapshots the file. |
   | Metadata version/folder/key drift | Reject preparation. Never silently refresh an old cleanup target from current metadata. |
   | Duplicate/stale cleanup execution | May repeat only the permanently abandoned original target; stale database outcomes must be fenced. |

   The unsafe sequence is: check UPLOADING → release locks → another transaction commits READY → DELETE. A live claim immediately before DELETE does not eliminate it.

   A later folder purge may snapshot an already abandoned FAILED file. B4 must preserve that snapshot and must not claim folder completion. Current folder processing still rejects admitted non-READY members as `UNSAFE_WRITER`, even with a B3 receipt.

8. **Minimum durable state is small, but its meaning must be explicit.**

   A safe implementation needs:

   - One retained cleanup intent per allocation, referencing immutable allocation/receipt identity, the accepted metadata snapshot, decision reason and PostgreSQL decision time.
   - Permanent exclusion of READY after abandonment. An UPLOADING → FAILED CAS and intent insertion must commit atomically. Every READY path, including retained `FileMetadataService.markReady`, must remain unable to cross that decision.
   - A recoverable claim token/revision with bounded lease and retry state for competing executors. Claim acquisition must commit before I/O.
   - A separate immutable DELETE/verified-absence receipt, recorded only after the adapter confirms that outcome.
   - Permanent allocation retention and a claim fence when recording results.

   Resource order remains hierarchy → fresh file → allocation → cleanup progress → final claim/job fence. No lock spans Garage I/O. Expired claims fence database writes; they do not cancel DELETE.

   Do **not** reuse V20 `write_precluded_at`: its constraint deliberately excludes admitted allocations. Do not fabricate never-written proof or reuse existing purge success semantics.

9. **Several cases must remain blocked without an arbitrary expiry date.**

   These include admitted UPLOADING/FAILED allocations without genuine receipts; successful PUT followed by failed closure or missing receipt commit; historical admissions without V25 snapshots; and any allocation whose possible additional writer remains unaccounted for.

   Unknown original bindings, identity corruption and missing metadata without retained cleanup authority also remain blocked until independently resolved. Age, HEAD observations, process death, cancellation and exhausted retries cannot resolve them.

   Receipt-backed files under existing purge coordination remain deferred under today’s implementation. Supporting them requires an explicit purge-proof extension; B4 must not bypass that boundary.

10. **The smallest defensible implementation slice is explicit cleanup of matching receipt-backed FAILED files outside purge coordination.**

   Keep receipt-backed UPLOADING restore/recovery behavior unchanged. Do not add scheduling, metadata removal, folder-proof integration or historical repair. Persist intent, claim it, execute bounded DELETE through `ObjectStorageService`, and retain the outcome.

   This subset may be small or empty in ordinary foreground operation because B3 does not normally mark FAILED after success. That limitation should be reported rather than broadening eligibility silently.

   Required acceptance evidence:

   - Deterministic READY/FAILED/cleanup, duplicate-claim and folder-purge winner orders.
   - Atomic intent/transition rollback and lost-acknowledgement replay.
   - Crashes before DELETE, after DELETE and before receipt persistence.
   - Stale claims cannot record progress; delayed DELETE remains harmless against the abandoned, never-reused target.
   - Real HTTP request counting with the production SDK configuration for disconnects, 5xx, redirects and response loss.
   - Delayed uncertain PUT fixtures receive no cleanup, regardless of age or HEAD.
   - Binding/version/ownership/ancestry drift, missing metadata and sealed membership fail closed.
   - Disposable Garage objects disappear only for eligible targets; unrelated controls remain readable.
   - Focused and full verification pass before any implementation commit or enablement.

11. **The GO is bounded by the existing operational contract.**

   It does not cover independent overwrites, request replay outside the audited client, namespace reassignment, storage-side fencing or multi-node failure guarantees. If the single-dispatch premise cannot be maintained or verified, **defer automatic cleanup**. Additional timestamps, leases or terminal flags cannot repair that missing premise.

   Read-only VM inventory confirmed the running Garage image is `dxflrs/garage:v2.3.0`. No database or object operations were performed. Test results cited in existing documents remain historical evidence; this review established no new passing test run.
