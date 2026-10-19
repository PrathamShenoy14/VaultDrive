# ADR-0003: JWT authentication with PostgreSQL session revocation

- Status: Accepted; extended by the authentication-session milestone on 2026-10-19
- Date: 2026-10-09 (retrospective record)

## Context

The API needs authentication usable by future web and mobile clients without server-side HTTP sessions.

## Decision

Hash passwords with BCrypt. After credential verification, issue short-lived HS256 JWT access tokens whose subject is the user's UUID. Validate signature, issuer, and expiry with Spring Security's OAuth2 resource-server support. Accept bearer tokens in the `Authorization` header without server HTTP sessions; PostgreSQL now supplies revocation state as described below.

## Consequences

- API instances now share PostgreSQL refresh-session and revocation state for access-token validation (see the extension below).
- A symmetric signing secret is operationally simple but every verifier capable of validating the token also holds signing material.
- The original implementation used 15-minute access tokens without refresh/logout. The session extension below supersedes that behavior.
- Account-state controls remain deferred.
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
is independent of these file capabilities.

## Authentication-session extension (2026-10-19)

Keep bearer JWT transport, BCrypt and existing authorization. Issue 30-minute
access tokens with unique jti values; login/refresh also bind sid to a PostgreSQL
family. Every JWT request checks indexed jti revocation plus family revocation
when sid is present, failing closed on missing/unavailable state. This knowingly
adds a PostgreSQL availability dependency to authentication. Pre-milestone JWTs
without jti require login again on rollout.

Use 256-bit SecureRandom opaque refresh credentials and SHA-256 hashes, with
30-day absolute family expiry. Retain consumed hashes to detect reuse. Lock the
family, then freshly read its state and token, consume and replace in one
transaction. Commit family revocation before returning a replay error. No
in-process mutex or grace window: concurrent refresh and lost-response retries
have the same strict replay outcome. This follows the rotation trade-off in
[RFC 9700 section 4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14).

Use host-only Secure HttpOnly Strict cookies, a session-bound synchronizer header
and custom-header login admission with CORS disabled. Logout atomically revokes
the matching family and presented jti; family checks invalidate its other access
tokens too. Revocation expiry includes JWT clock tolerance; bounded explicit
cleanup uses SKIP LOCKED. No Redis, HTTP session, browser UI, key-management or
general rate-limit feature is added. See [the complete contract and runbook](../authentication-sessions.md).
