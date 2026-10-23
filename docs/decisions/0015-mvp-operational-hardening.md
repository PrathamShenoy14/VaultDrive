# ADR-0015: Bounded MVP admission and explicit operational recovery

- Status: Implemented; deployment prerequisites remain gated
- Date: 2026-10-23

Use four fixed process-wide token buckets and nonwaiting semaphores before bearer
database validation: authentication, multipart control, authenticated downloads,
and other metadata work (including search, activity and copy). No client-keyed
maps, forwarded-IP trust, Redis or distributed limiter is introduced. An edge
must enforce client fairness and fleet-wide limits. Reject with the common 429
envelope, no-store and Retry-After: 1. A rejection never enters business code.

Authenticated downloads retain admission across storage open and async streaming.
A bounded executor replaces MVC's unbounded default. Completion, timeout, error,
executor rejection and cancellation close queued streams. A running callback keeps
its permit until its actual exit, even after cancellation; an unresponsive task
reduces capacity rather than spawning replacements. Partial SDK response closure
aborts its transfer instead of draining the remaining object. Authorization still
commits before storage opens; admitted downloads may finish after revocation.

Logical multipart expiry and historical SUCCEEDED-file metadata finalization each
have an independently opt-in, single-thread, fixed-delay, bounded keyset poller.
They reuse the existing transaction protocols. Expiry never initiates Abort;
finalization never repeats storage deletion. Restarts begin a new safe sweep.
Existing publisher/consumer/job/missing-job protocols and all lock keys/orders
remain. No storage or broker I/O is performed in a metadata transaction.

A deployment profile bounds servlet/database resources and requires every
necessary processor, readiness probing, OSIV=false, schema validation, explicit
topology, confirmed publication, no template retries and an HTTPS storage endpoint.
Validation runs before runtime-bean initialization, not after workers have started.
Local autonomous defaults remain false. No mixed-version rollout is supported.

Liveness is process-only. Public readiness exposes only UP/DOWN from a cached,
bounded, single-thread probe. Sanitized local logs provide dependency, consumer,
worker, capped work-count and admission signals. Stale snapshots fail closed.
Readiness is strict about stalled/exhausted processing work. Aged retained business
recovery and logical-expiry storage uncertainty remain alerts, keeping their client
recovery APIs reachable without inventing storage authority or reclamation evidence.

V45 adds retained operator audit and permits one exact FAILED job to receive
1..10 additional failures, capped at 1,000. Job locking precedes fresh counters
and audit insertion; it acquires no resource locks. Counters, checkpoints, identity,
receipts and failure history remain monotonic. The database rejects unaudited
budget changes or terminal reactivation. This supersedes V18's immutable maximum
only for an audited extension; regular retries remain bounded by the persisted
maximum. Existing handlers still decide eligibility and storage safety.

An isolated nonweb operator profile disables every poller/consumer and migrations.
One command inspects capped signals, replays 1..20 exact retained FAILED/PUBLISHED
event UUIDs through strict normal ingestion, or extends exact exhausted job
budgets. Replay bypasses transport only, never source validation or worker fences.
It leaves publication/quarantine/history intact and records SUCCEEDED or UNKNOWN;
an unknown commit can be replayed safely because ingestion is idempotent. No
public admin API, automatic consumer restart, uncertain storage dispatch, terminal
success fabrication, evidence repair or broad historical backfill is introduced.

See [configuration, monitoring, recovery and launch gates](../operational-hardening.md).
