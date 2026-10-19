# ADR-0010: Consolidated backend and historical upload boundary

- Status: Accepted and implemented
- Date: 2026-10-20

## Decision

Support direct multipart as the only new-upload protocol. Retire synchronous
POST `/api/v1/files`, SERVER_PUT allocation/admission/PUT and foreground receipt
creation. Supply no compatibility fallback. Preserve migrations, original storage
identities, uncertain effects, durable receipts and bounded historical recovery.
This supersedes the synchronous runtime portions of ADR-0002, ADR-0005 and ADR-0009;
their historical records and accepted failure-handling principles remain.

Keep JPA for managed metadata/outbox insertion, native EntityManager SQL for
PostgreSQL authority/evidence/conditional transitions, and JdbcTemplate for
authentication sessions/revocations. These use the existing datasource/transaction
manager and own distinct responsibilities. Do not mechanically convert frameworks
or introduce duplicate repository/service protocols. Keep OSIV disabled, protected
reads after locks, refresh/clear rules, stable advisory keys and exact lock order.
Broker/storage I/O remains outside metadata transactions.

Share notification transport retry/quarantine/ACK policy while retaining typed
parsers, separate transactional ingestion and isolated routing. Remove superseded
resource-listing queries and legacy upload tests; reconstruct historical states
only with explicitly imported test fixtures.

## Consequences

No applied migration or durable record is rewritten. SERVER_PUT remains a historical
schema value/default, not supported application admission. Missing old receipts
remain unresolved. Retention, unknown multipart reclamation, zero-byte support and
historical binding changes require independent decisions. Deployment must retire
older writers; deleting Java admission does not fence an already running old binary.
No lock redesign, performance claim, new feature or autonomous default is included.

See the [audit, retained paths and verification](../backend-consolidation.md) and
[current architecture](../architecture.md).
