# ADR-0004: Owner-scoped folder namespace and uniqueness

- Status: Accepted and implemented
- Date: 2026-10-09 (retrospective record)

## Context

VaultDrive needs nested folders, root folders, ownership isolation, sibling-name uniqueness, soft deletion, moves, and restoration.

## Decision

Represent folders as an owner-scoped adjacency list with nullable `parent_folder_id`. Enforce same-owner parentage through a composite foreign key and active sibling-name uniqueness through a partial unique index on `(owner_id, parent_folder_id, name)` with `NULLS NOT DISTINCT`. Treat names as trimmed, validated, and case-sensitive. Validate ancestor accessibility and move cycles in application code.

## Consequences

- Root and each parent folder form separate namespaces.
- Soft-deleted rows release their names; restore resolves collisions with a deterministic suffix.
- Deleting an ancestor makes descendants inaccessible without recursively updating every descendant.
- Ancestor traversal is straightforward but performs repeated reads and can become costly for deep trees.
- The database prevents direct self-parenting and cross-owner parents, but not arbitrary multi-row cycles; the service must retain cycle checks.
- Case-sensitive uniqueness means `Report` and `report` can coexist. Changing this later requires a migration and collision policy.

## Alternatives considered

- Materialized paths or closure tables: faster ancestor/subtree queries but more complex write maintenance.
- Recursive soft deletion of every descendant: direct state, but expensive and harder to reverse safely.
- Case-insensitive uniqueness: familiar on some platforms, but not chosen by the current schema.
