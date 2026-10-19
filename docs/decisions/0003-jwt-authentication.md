# ADR-0003: Stateless JWT authentication

- Status: Accepted and implemented for access tokens
- Date: 2026-10-09 (retrospective record)

## Context

The API needs authentication usable by future web and mobile clients without server-side HTTP sessions.

## Decision

Hash passwords with BCrypt. After credential verification, issue short-lived HS256 JWT access tokens whose subject is the user's UUID. Validate signature, issuer, and expiry with Spring Security's OAuth2 resource-server support. Keep the API stateless and accept bearer tokens in the `Authorization` header.

## Consequences

- API instances do not need shared session state for access-token validation.
- A symmetric signing secret is operationally simple but every verifier capable of validating the token also holds signing material.
- Tokens remain valid until expiry; logout, revocation, refresh tokens, and account-state invalidation are not implemented.
- The current 15-minute lifetime limits but does not eliminate the revocation gap.
- Secret generation, storage, and rotation are deployment responsibilities still to be designed.

## Alternatives considered

- Server sessions: simpler revocation but shared state/cookie concerns for multiple client types.
- Asymmetric JWT signing: cleaner separation between issuer and verifier, but more key-management work than the current milestone requires.
- Opaque tokens with introspection: central revocation/control at the cost of a runtime dependency on the authorization service.

## Sharing M4: isolated file bearer capabilities

[File Viewer links](../sharing-m4.md) are separate from JWT authentication. Two
narrow public GETs accept exactly one `X-VaultDrive-Share-Token` header through a
dedicated stateless security chain; link secrets never become principals or grant
ordinary API/session authority. The database stores SHA-256 hashes of 256-bit
random secrets, immutable file/owner identity, finite bounded expiry and permanent
revocation. Fresh PostgreSQL checks gate each metadata/download admission.

Public DTOs reveal only name, content type and size. Invalid, expired, revoked and
lifecycle-blocked links share one 404 body. Query strings are rejected. All link
responses use no-store/no-referrer/nosniff; downloads use attachments and stream
through the backend without presigned GETs. A token bucket and semaphores bound
public request rate, concurrent requests and full download lifetimes per process.
Production HTTPS, proxy/APM/header/body redaction, edge timeouts and multi-instance
admission remain deployment responsibilities. JWT lifetime/revocation behavior
above is unchanged.
