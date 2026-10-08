# ADR-0001: Spring Boot, PostgreSQL, and Flyway

- Status: Accepted and implemented
- Date: 2026-10-09 (retrospective record)

## Context

VaultDrive needs a typed REST backend, relational constraints for ownership and namespaces, transactional metadata changes, and an explicit schema history suitable for learning and review.

## Decision

Use Java 21 with Spring Boot, Spring MVC, Spring Data JPA, validation, and Spring Security. Store relational state in PostgreSQL. Manage schema evolution with append-only Flyway migrations and configure Hibernate with `ddl-auto=validate`.

## Consequences

- Spring provides a cohesive web, dependency-injection, transaction, persistence, security, and testing stack.
- PostgreSQL can enforce composite foreign keys, partial unique indexes, and `NULLS NOT DISTINCT` semantics used by VaultDrive.
- Schema changes are explicit and reviewable; application startup fails on mapping/schema drift.
- The project depends on PostgreSQL-specific capabilities and requires database infrastructure for integration tests.
- JPA convenience does not remove the need to reason about SQL, transaction boundaries, locking, and query behavior.

## Alternatives considered

- Hibernate schema generation: simpler initially, but weaker migration discipline and reviewability.
- An embedded database for all tests: faster setup, but it would not prove PostgreSQL-specific constraints.
- A document database: less natural for the current relational ownership and uniqueness invariants.
