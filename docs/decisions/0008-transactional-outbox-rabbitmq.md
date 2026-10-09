# ADR-0008: Transactional outbox and durable asynchronous jobs

- Status: Accepted; planned, not implemented
- Date: 2026-10-09

## Context

Purge, reconciliation, and other background work must survive process restarts and failures across PostgreSQL, RabbitMQ, and Garage. Publishing directly from an API transaction can lose a notification after commit or publish work for a transaction that later rolls back. RabbitMQ delivery can be duplicated or missed, and broker state alone is not sufficient as VaultDrive's durable record of job progress.

ADR-0006 left database polling versus a broker/outbox open. This ADR resolves that topology decision without defining its implementation schema or operational tuning.

## Decision

Use a PostgreSQL transactional outbox with RabbitMQ notifications and PostgreSQL-backed durable job state. PostgreSQL remains authoritative for business metadata, outbox events, job state, and recovery checkpoints.

API transactions will atomically persist the business-state change and corresponding outbox event. A separate publisher will deliver committed outbox events to RabbitMQ and tolerate duplicate publication. RabbitMQ will notify workers that work is available; it will not be the sole durable job record.

Workers will claim durable jobs in PostgreSQL and process them idempotently. A scheduler will scan PostgreSQL to publish missed outbox events, recover missed notifications or abandoned work, and drive bounded retries. Database polling is therefore a recovery and publishing mechanism within the RabbitMQ design, not an alternative worker topology.

Garage/S3 calls will remain outside metadata database transactions. Workers will persist claims or conditional state transitions in short transactions, perform object-storage I/O separately, and then conditionally record known outcomes. Duplicate delivery, worker crashes, and uncertain storage outcomes must remain safe to retry.

## Consequences

- A committed business transition and its event cannot diverge at the API transaction boundary.
- RabbitMQ reduces notification latency while PostgreSQL preserves durable truth and enables recovery when publication or delivery is missed.
- Publishers and consumers must assume at-least-once effects and be idempotent; exactly-once delivery is not required.
- Purge and reconciliation workers must preserve the lifecycle, hierarchy, ownership, and lock-order invariants in ADRs 0005–0007.
- The earlier polling-first possibility in ADR-0006 is superseded by this decision.
- No outbox, RabbitMQ, durable-job, worker, scheduler, retry, or recovery component is implemented by this ADR.

## Deferred decisions

Queue and exchange names, routing, database schemas, job states, claim and fencing protocol, lease duration, retry/backoff counts, terminal-failure handling, retention, ordering, publisher batching, and operational thresholds remain open for bounded implementation work.
