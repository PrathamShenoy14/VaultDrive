# ADR-0002: Separate metadata from Garage object storage

- Status: Accepted and implemented
- Date: 2026-10-09 (retrospective record)

## Context

File bytes are large, streamed, and not suited to relational queries, while names, ownership, hierarchy, lifecycle, and authorization need transactional relational state.

## Decision

Store file metadata in PostgreSQL and bytes in Garage through the S3-compatible AWS SDK. Keep the provider behind `ObjectStorageService`. Generate opaque immutable keys from owner and file UUIDs rather than user-visible paths.

## Consequences

- Rename and move are metadata-only operations; storage objects do not need copying.
- The S3-compatible boundary allows adapter testing and future provider changes without changing domain services.
- PostgreSQL and Garage cannot be updated atomically. Upload, deletion, retry, and reconciliation must explicitly handle partial failure.
- Application servers currently proxy uploads and downloads, which is simple but may become a throughput bottleneck. Presigned/resumable transfers are planned, not implemented.

## Alternatives considered

- Store blobs in PostgreSQL: one transaction boundary, but poor fit for scalable object transfer and storage economics.
- Encode folder/name in the object key: human-readable, but rename/move would become expensive object operations.
- Use a provider-specific service directly in file logic: less abstraction, but tighter coupling and harder testing.
