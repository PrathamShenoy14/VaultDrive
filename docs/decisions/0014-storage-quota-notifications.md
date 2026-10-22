# ADR-0014: Atomic logical quota threshold notifications

- Status: Implemented
- Date: 2026-10-22

Use the existing quota accounting row's OLD/NEW logical used-plus-reserved charge
and limit as the threshold state. An AFTER UPDATE trigger appends an immutable
V44 quota snapshot and existing transactional outbox pointer for each newly
reached 80/90/100 boundary. Exact numeric comparisons avoid overflow/rounding.
Charge must be positive, so an empty zero-limit owner stays quiet. Falling
strictly below a boundary rearms it; a later crossing gets a new UUID. Limit
edits and explicit counter repairs follow the same rule. There is no historical
backfill, cooldown or notification-delivery dependency.

This keeps every supported accounting path atomic, including delegated Editor
reservation, multipart lifecycle, copy members and bounded purge removal, without
duplicating crossing logic in services. Counter/limit updates already lock the
owner quota row; only new immutable source/outbox rows follow. Existing resource
coordination remains unchanged. No external I/O enters a database transaction.

Reuse the existing permission notification route, listener, bounded retry/ACK
policy, persistence table and JWT APIs. Quota sources supply their owner as sole
recipient. A conditional database source guard replaces the permission-only FK
and validates both immutable source types; source deletion remains forbidden.
Existing event/recipient uniqueness guards competing consumption and preserves
read state on replay. Separate source snapshots keep quota values immutable and
avoid placing a second copy in notification rows. Existing grant display remains
authorization dependent; quota alerts expose only informational numeric snapshots.

A second queue/consumer/persistence pipeline and a per-threshold latch table
were rejected as unnecessary: serialized counters and limit already define
rearming, and the existing route has the required durable recipient semantics.
Mixed old/new consumers are unsupported; deploy the migration and updated binaries
together before publishing quota events. At-least-once delivery can be delayed,
duplicated or reordered. Existing explicit operator replay remains necessary for
quarantine or broker loss after publication. No delivery SLA, automatic scanner,
retention/erasure policy, physical reclamation or frontend is added.

See [threshold, API and operational contract](../quota-notifications.md),
[quota accounting](0011-storage-quota-accounting.md),
[locking](0007-concurrency-locking.md) and
[outbox delivery](0008-transactional-outbox-rabbitmq.md).
