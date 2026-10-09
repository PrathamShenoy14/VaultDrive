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
10. Keep commits coherent by feature or bounded task; do not create unnecessary commits for every small edit. Follow the Git and commit policy below.

## Development cycle

Use the workflow in `docs/development-workflow.md`. In short: discuss the what/why/how and trade-offs, define acceptance criteria, implement a bounded task with tests, run targeted then full verification, inspect the diff, update learning/architecture notes, and follow the Git and commit policy below.

## Git and commit policy

- You may automatically create local Git commits when a coherent, verified task is complete.
- Use concise, descriptive commit messages.
- Stage only files relevant to the completed task, using explicit paths. Preserve unrelated user changes.
- Inspect the staged file list and complete staged diff before committing.
- Never commit credentials, secrets, private keys, or local environment files. Sanitized example files without real secrets may be committed.
- Never push, force-push, amend existing commits, rebase, reset, or otherwise rewrite Git history without the user's explicit approval.
- Do not commit if tests or required validation fail; report the failure instead. Required verification must pass before committing.
- If a task explicitly says not to commit, respect that instruction.
- Keep commits coherent by feature or bounded task; do not create unnecessary commits for every small edit.

## Codex CLI execution policy

- Use only GPT-5.6 Sol, GPT-6 Sol, or GPT-6.1 Sol. Never use Astra.
- Prefer `low` reasoning for mechanical changes, `medium` for normal implementation, and `high` only for genuinely difficult concurrency or distributed-systems problems.
- Work directly in the checked-out repository from the Zed terminal.
- Treat the supplied task scope, acceptance criteria, and non-goals as the implementation boundary.
- Do not independently redesign an already-agreed architecture unless a concrete correctness or security issue requires reconsideration.
- Before implementation, inspect only the relevant files, tests, and architecture decisions. Do not repeat repository-wide audits when the necessary context is documented.
- Prefer existing services, repositories, abstractions, and test patterns over introducing unnecessary new layers.
- Read `docs/architecture.md` and relevant ADR sections selectively; do not load all documentation for every task.
- If a significant design ambiguity or unsafe assumption is discovered, explain it before making a major architectural change.
- Run focused tests during implementation and the full relevant verification suite before committing.
- Follow the Git and commit policy above; local commit permission does not authorize pushing or rewriting Git history.
- After completing a task, report the changed files, key decisions, test results, commit hash if a commit was created (otherwise state that no commit was made), and remaining limitations.
- Keep responses concise and avoid repeating repository background already captured in documentation.

## Baseline verification

In PowerShell, load the ignored local environment file in the same shell that runs Maven:

```powershell
cd backend; . .\.env.local.ps1; .\mvnw test
```

Repository and S3 integration tests require the configured PostgreSQL test database and, for the S3 suite, a reachable S3-compatible service with credentials. If infrastructure is unavailable, report the exact skipped or failed checks; do not call the task fully verified.

Before committing:

```powershell
git diff --check
git status --short
git diff --cached --name-only
git diff --cached --check
git diff --cached
```

The staged file list must contain only the intended task.

## Ubuntu VM infrastructure access

- Docker, PostgreSQL 17, and Garage 2.3.0 run inside an Ubuntu VirtualBox VM.
- Windows connects through the SSH alias `vaultdrive-vm`; SSH key authentication is configured.
- Automatically use SSH when infrastructure inspection is relevant. For non-interactive VM operations, use `ssh -o BatchMode=yes vaultdrive-vm "<command>"`.
- Inspect Docker containers, logs, and the PostgreSQL schema when necessary.
- Never read or expose SSH private keys, passwords, or service credentials.
- Never perform destructive Docker, database, storage, or VM operations without explicit user approval.
- Do not modify unrelated infrastructure.
