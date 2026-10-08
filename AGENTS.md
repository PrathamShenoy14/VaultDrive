# VaultDrive contributor guide

## Scope and intent

VaultDrive is a learning and portfolio project for production-minded backend engineering. Prefer small, complete vertical slices that make the system easier to explain. Preserve the distinction between behavior verified in the repository and ideas that are only planned.

## Repository map

- `backend/`: Java 21 and Spring Boot backend.
- `backend/src/main/resources/db/migration/`: append-only Flyway migrations.
- `backend/src/test/`: unit, controller, repository, security, concurrency, and S3 integration tests.
- `docs/architecture.md`: current system and implementation-status map.
- `docs/development-roadmap.md`: ordered work, exit criteria, and open decisions.
- `docs/decisions/`: architecture decision records (ADRs).
- `docs/learnings.md`: concepts and trade-offs learned from completed work.

## Working rules

1. Inspect `git status`, the relevant production code, migrations, configuration, and tests before proposing or editing behavior.
2. Do not overwrite, discard, stage, or commit unrelated user changes. Stage explicit paths only.
3. Keep production changes, tests, migrations, and documentation consistent. Never describe planned behavior as implemented.
4. Schema changes use a new Flyway migration; never edit an applied migration merely to change the current schema.
5. PostgreSQL constraints are the final guard for invariants such as uniqueness. Application checks exist for clear errors, not as a replacement for constraints.
6. Keep object bytes behind `ObjectStorageService`; PostgreSQL stores searchable metadata and lifecycle state.
7. Preserve ownership checks and ancestor accessibility for every file/folder operation. Do not leak another user's resource through different error behavior.
8. For namespace mutations, document the lock order and ensure the protected row is read after the relevant lock is acquired. Any change to locking requires concurrency tests.
9. Do not claim performance improvements without a reproducible measurement. Record an unknown when evidence is absent.
10. Keep commits coherent by feature or bounded task. Do not push unless explicitly requested.

## Development cycle

Use the workflow in `docs/development-workflow.md`. In short: discuss the what/why/how and trade-offs, define acceptance criteria, implement a bounded task with tests, run targeted then full verification, inspect the diff, update learning/architecture notes, and commit only the validated task.

## Codex execution policy

- Use a Sol model only: GPT-5.6 Sol, GPT-6 Sol, or GPT-6.1 Sol.
- Use reasoning effort no higher than `high`; prefer `medium` for bounded implementation and `high` for architecture, concurrency, or difficult debugging.
- Do not use Astra for this repository.
- Give Codex a bounded file/behavior scope, explicit non-goals, acceptance criteria, and exact verification commands.
- Codex may edit the checked-out repository directly; copy/paste handoff is unnecessary.

## Baseline verification

From `backend/` on Windows:

```powershell
.\mvnw.cmd test
```

Repository and S3 integration tests require the configured PostgreSQL test database and, for the S3 suite, a reachable S3-compatible service with credentials. If infrastructure is unavailable, report the exact skipped or failed checks; do not call the task fully verified.

Before committing:

```powershell
git diff --check
git status --short
git diff --cached --name-only
```

The staged file list must contain only the intended task.
