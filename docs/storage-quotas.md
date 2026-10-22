# Storage quota operations

Copy M1 reserves the source's full logical length for the owner's new UPLOADING
file, atomically with its independent allocation and retained idempotent intent.
READY transfers reserved to used once. Replays reserve nothing again; collision
or exhausted quota rolls the whole admission back. Source Trash/deletion does not
change the destination's charge. UNKNOWN, positive receipt and BLOCKED remain
reserved. Explicit logical cancellation commits FAILED and releases the charge;
sealed folder logical discard releases it on metadata removal. Both retain
possible late bytes in the uncertain-allocation inventory, never physical
reclamation proof. See [copy recovery and cancellation](file-copy.md).

The MVP owner limit defaults to 10 GiB (10,737,418,240 bytes). An operator can
change `storage_quotas.limit_bytes` for an existing owner in a short transaction.
Lowering it below current used plus reserved does not remove data; available
becomes zero until enough logical charge is released. There is no public
limit-edit API.

GET `/api/v1/users/me/storage` uses the bearer identity. Read `usedBytes`,
`reservedBytes`, `availableBytes`, `uncertainStorageBytes`,
`uncertainStorageAllocations` and `unknownSizeAllocations` together. An allocation
can appear in both reserved and uncertain storage. The uncertain length is the
sum of retained known logical lengths for admitted non-READY allocations, not
Garage capacity use; part overhead and actual block reclamation are unknown.

For a discrepancy, enumerate owners using the internal
`StorageUsageService.candidates(after, limit)` with `limit` 1..100. For each
owner call `reconcile(owner, false)` and inspect recorded/derived counters. Once
the cause is understood, call `reconcile(owner, true)` in a separate transaction.
Do not wrap a page in one transaction. A timeout needs deliberate retry or
investigation, not a blind counter update. The exclusive hierarchy lock gives
the repair a fresh aggregate against supported writers. Repair cannot establish
Garage reclamation or clear unknown allocations.

An UPLOADING file after uncertain Create/Complete/CopyObject or blocked recovery remains
reserved. Use the matching multipart completion/cancellation/expiry or
[copy recovery/cancellation](file-copy.md) procedure to establish a durable eligible
transition; never free it based on time or
provider absence. FAILED/removed allocations can contribute to uncertain storage
indefinitely. Investigate original receipts, storage identities and purge runbooks
before any manual repair. Do not rewrite or delete retained allocations to fix
an apparent quota discrepancy.

The 2026-10-21 repository verification passed 1,331 backend tests with zero
failures/errors and 20 separately gated skips. That result includes real
PostgreSQL quota races, Editor uploads, completion/cancellation/expiry and
purge accounting, and enabled Garage multipart and RabbitMQ suites. It is not
a measurement of Garage GC, quota contention or production capacity.
