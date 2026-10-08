# Development workflow and verification

## 1. Discuss before coding

For each feature, write down:

- **What** user-visible behavior or invariant changes.
- **Why** it is worth adding now.
- **How** data, transactions, external systems, security, and failure recovery work.
- **Trade-offs** and deliberately deferred alternatives.
- **Unknowns** that need a spike or measurement.

Use examples from VaultDrive and keep planned behavior clearly labelled.

## 2. Bound the implementation task

A handoff should name the allowed files or component, non-goals, acceptance criteria, and verification commands. Prefer a feature slice that can be reviewed and committed independently. Do not combine refactoring, a new feature, and unrelated cleanup.

Use GPT-5.6 Sol, GPT-6 Sol, or GPT-6.1 Sol only. Use `medium` reasoning for routine bounded work and at most `high` for architecture, concurrency, or difficult debugging. Do not use Astra.

## 3. Define done before editing

A backend task is complete only when applicable items are satisfied:

- API status/body and authorization behavior are explicit.
- Database invariants and migration effects are explicit.
- Transaction boundary and lock order are explicit.
- PostgreSQL/Garage partial failures have a documented outcome.
- Unit tests cover branching logic.
- Controller tests cover mapping, authentication, and response contract.
- Repository/integration tests cover real constraints and queries.
- Concurrency-sensitive behavior has a deterministic integration test.
- Documentation states what is implemented and what remains planned.

## 4. Verify in layers

From `backend/`, run the smallest relevant test first:

```powershell
.\mvnw.cmd "-Dtest=FileMetadataServiceTest" test
```

Then related integration tests as needed, followed by:

```powershell
.\mvnw.cmd test
```

Infrastructure-dependent tests require the documented PostgreSQL test database and S3-compatible service. Record exact failures when dependencies are absent. Never replace a failed integration check with an unsupported claim that the behavior works.

For documentation-only work, also check:

```powershell
git diff --check
git diff --name-only
git status --short
```

Verify local Markdown links resolve and search for contradictory status words such as `implemented`, `planned`, and `working tree`.

## 5. Review and learn

Before committing:

- inspect the complete diff;
- confirm no secrets, generated output, editor settings, or unrelated user changes are staged;
- update the relevant ADR when a durable trade-off changes;
- update `docs/learnings.md` with the concrete what/why/how and failure modes;
- explain the changed code path from controller to persistence/external service.

## 6. Commit policy

Stage explicit paths and inspect the index:

```powershell
git add <explicit-paths>
git diff --cached --check
git diff --cached --name-only
git diff --cached
```

Commit one coherent feature or documentation phase. Do not push unless the user asks. Existing uncommitted work must remain unstaged and untouched.

## Suggested Codex handoff template

```text
Repository: <absolute path>
Task: <one bounded outcome>

Context:
- Current behavior and relevant ADRs.
- Why the change is needed.

Scope:
- Files/components allowed to change.

Non-goals:
- Explicitly deferred work.

Acceptance criteria:
1. Observable behavior.
2. Data/transaction/concurrency invariants.
3. Required tests and documentation.

Verification:
- Targeted command(s).
- Full regression command.
- Diff/status checks.

Constraints:
- Preserve unrelated changes.
- Do not invent benchmarks.
- Commit only after all required checks pass; do not push.
```
