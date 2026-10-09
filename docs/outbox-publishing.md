# Outbox publishing development and recovery (Phase 1C)

## Implementation boundary

The backend now supplies a PostgreSQL leased outbox publisher and RabbitMQ confirm/return transport. It does not consume notifications, create durable purge jobs, delete Garage objects, or backfill old purge intents. PostgreSQL remains authoritative; `PUBLISHED` describes notification acceptance, not deletion. [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md) records the protocol, payload versioning, topology, and failure trade-offs.

## Windows connectivity and private configuration

Keep the [Phase 1A SSH tunnel](rabbitmq-development.md) running before enabling publication or executing broker tests:

```powershell
ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -N -L 127.0.0.1:5672:127.0.0.1:5672 -L 127.0.0.1:15672:127.0.0.1:15672 vaultdrive-vm
```

AMQP is Windows `127.0.0.1:5672`, forwarded to VM loopback through the SSH alias's localhost port `2222`. Management is `http://127.0.0.1:15672/`. No extra NAT rule or public binding is needed.

Supply `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` through the ignored `backend/.env.local.ps1` or equivalent process environment. Use the VM development credentials privately; never paste them into tracked files, command arguments, URLs, logs, or this documentation. `RABBITMQ_HOST`, `RABBITMQ_PORT`, and `RABBITMQ_VHOST` override the default localhost/5672/`/` connection. The sanitized example contains placeholders only.

`OUTBOX_PUBLISHER_ENABLED` defaults to `false`. Explicitly enable it only after approving declarations of the durable exchange `vaultdrive.outbox.events`, quorum queue `vaultdrive.purge.requests`, and `purge.requested` binding in the selected vhost. Enabling the app also permits publishing pending notifications there; it is separate from creating infrastructure-only containers. Phase 1C verification uses isolated test topology and does not enable the running development API or declare these default objects in `/`.

The transport requires correlated confirms, publisher returns, and mandatory sends. Application properties configure those settings and disable in-memory RabbitTemplate retries; durable retry scheduling belongs to PostgreSQL. Connection timeout is three seconds and channel RPC timeout five seconds; confirm wait is ten seconds. A network send can remain blocked independently of the confirm wait; expired leases let other instances recover, but do not guarantee that every socket write completes within the lease.

## Lease and retry configuration

| Property prefix `vaultdrive.outbox.publisher.` | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Explicitly opt in to polling and broker I/O |
| `max-attempts` | `10` | Automatic attempt budget, including abandoned claims |
| `max-events-per-poll` | `20` | Bounded poll work; each event is claimed just before sending |
| `lease` | `30s` | Database-clock ownership window; must exceed confirm timeout |
| `confirm-timeout` | `10s` | Wait for correlated broker confirmation |
| `initial-backoff` / `max-backoff` | `1s` / `60s` | Exponential retry delay, capped at maximum |
| `poll-delay` | `5s` | Delay after one poll finishes |
| `exchange` / `queue` / `routing-key` | See above | Approved durable destination topology |

Known failures schedule the next retry from the failure-recording database time. Claims also persist an abandoned-attempt retry deadline equal to lease expiration plus backoff. Recovery waits for both expiration and that retry time. Late success/failure updates are rejected after expiration or token replacement. Stable event IDs allow future consumers to recognize replay; lease tokens do not fence RabbitMQ sends, so paused publishers may overlap after expiry.

Raw broker/database exception messages are not stored in the outbox. `last_failure_code` contains controlled classifications such as `NACK`, `RETURNED`, `TIMEOUT`, `BROKER_ERROR`, `INTERRUPTED`, `SERIALIZATION_ERROR`, `LEASE_EXPIRED`, or `ATTEMPT_LIMIT`. Confirmed completion clears it. Poller logs contain event IDs, attempt numbers, result codes, and exception class names only.

Framework AMQP and RabbitMQ-client loggers are disabled because connection diagnostics can render credential-bearing URIs. Use controlled publisher result codes and private operator inspection to diagnose failures; do not enable verbose connection logging in shared output.

## Operational recovery

- `PENDING`: wait until `next_attempt_at` and the next enabled poll.
- `PUBLISHING`: a committed lease exists. A crash or uncertain DB completion leaves it recoverable after expiry/backoff. Never forcibly complete it merely because a notification might have reached RabbitMQ.
- `PUBLISHED`: positive broker confirmation and no mandatory return were recorded by the current lease. This does not prove consumer execution.
- `FAILED`: the automatic attempt budget is exhausted. The event remains durable and requires an operator decision; it is not silently deleted or repeatedly hot-looped.

After diagnosing and correcting a failed publication, an explicitly approved operator action can rearm selected `FAILED` events with a new retry budget. Preserve event UUID, payload, aggregate reference, and creation time. Record the incident and prior attempt count before resetting the budget; no attempt-history table or recovery API exists in this slice. A representative parameterized update is:

```sql
UPDATE outbox_events
SET delivery_status = 'PENDING', attempt_count = 0,
    next_attempt_at = clock_timestamp(), last_failure_code = NULL
WHERE id = :event_id AND delivery_status = 'FAILED';
```

Run only with explicit approval against the intended database/event. The state constraint already requires failed rows to have no lease or publication timestamp. Increasing a configured attempt limit alone does not reactivate `FAILED` rows. Historical intents without outbox events still require a separate backfill/recovery design.

## Verification

The ordinary test profile disables automatic polling. Publisher database tests use a dedicated `outbox_publisher_test` schema within `vaultdrive_test`, migrated with Flyway, and delete only their own fixtures. Ordinary purge tests keep using the existing public test schema. This prevents publisher tests from claiming unrelated retained test events.

Real broker tests require the explicitly approved vhost `vaultdrive_outbox_test`, access for the private development user, the localhost tunnel, and `RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`. They declare UUID-suffixed `vaultdrive.outbox.events.test.*` exchanges and `vaultdrive.purge.requests.test.*` quorum queues, verify fixture publications/returns/replays, receive their own messages, then delete only their own topology. They never consume from the default production queue. The test vhost is retained; existing containers, services, networks, volumes, and default vhost topology are preserved.

Load ignored local configuration in the same PowerShell session that runs Maven:

```powershell
cd backend
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = "vaultdrive_outbox_test"
.\mvnw.cmd "-Dtest=OutboxPublisherIntegrationTest,OutboxPublisherTest,OutboxPublisherConfigurationTest,RabbitOutboxTransportTest,RabbitOutboxIntegrationTest,PurgeOutboxIntegrationTest" test
.\mvnw.cmd test
```

Without the test-vhost opt-in, live RabbitMQ tests are skipped; that is not full verification of Phase 1C. Negative confirmations and timeout outcomes are deterministically injected in transport tests; live tests verify positive confirms, persistence, mandatory returns, and replay after actual broker acceptance. Simultaneous file/folder requests cover both first-request commit and rollback. Process-kill, broker-restart persistence, multi-node failover, and production load experiments are not performed.

Verification on 2026-10-10: the focused database/transport/configuration/purge run passed 51 tests; the isolated live RabbitMQ run passed all three tests. The final full backend suite passed 383 tests with no failures, errors, or skips, including the additional claim-state constraint cases and existing PostgreSQL/S3 suites. V13 was exercised in the configured test database; the running development API and its database were not deployed or migrated by this slice. PostgreSQL/Garage identities, start times, restart counts, mounts, network endpoints, and port mappings matched the earlier complete baseline. RabbitMQ retained its original container identity, zero restart count, expected volume, captured network/address, and loopback port mappings. Test queues/exchanges were removed, the approved test vhost was retained, and verification-only SSH tunnels were closed.
