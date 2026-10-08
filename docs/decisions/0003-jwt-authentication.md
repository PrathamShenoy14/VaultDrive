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
