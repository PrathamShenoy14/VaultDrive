# ADR-0017: Same-origin Next.js BFF sessions for Frontend M1

Status: accepted for M1's explicitly single-process runtime, 2026-10-24.

## Context

The frozen backend issues bearer JWTs, a Secure/HttpOnly/Strict refresh cookie and a session-bound CSRF secret. Refresh rotation is single-use with reuse revoking its entire seven-day family. A tab-local promise cannot prevent races across tabs; middleware/RSC cannot become independent refresh owners. M1 requires real integration without changing this backend contract or infrastructure.

## Decision

Use an allowlisted Next.js Node Route Handler BFF. Keep JWT/CSRF/current backend cookies in bounded server-only memory keyed by an opaque 256-bit Secure/HttpOnly/Strict host cookie. Forward backend Set-Cookie values separately and unchanged. The BFF ignores browser Authorization, CSRF and refresh-cookie state and uses its current server-side credentials. The browser never receives the JWT/CSRF in readable responses or storage. Login/refresh return BFF 204; backend registration/profile/quota/error bodies remain authoritative.

Require exact configured HTTPS Origin plus custom-header admission for every BFF mutation. Forward backend login admission and session CSRF precisely; refresh omits bearer, logout includes it. No queries or automatic mutation retries. All personalized responses/fetches are no-store. One per-session async lock covers fresh state lookup, refresh, backend reads and logout across tabs/callers. Refresh occurs only when access is near expiry, and waiting callers use the newly stored credentials. Absolute expiry only decreases. Uncertain refresh destroys local credentials; only known admission-before-work 429 retains them for explicit later retry.

Protected RSC pages read authenticated profile data on the server. Expired access redirects to a restore page that POSTs through that same coordinator; rendering/prefetch never rotates. Revoked/invalid unexpired access leads to login without rotation. Login/logout use full navigation to discard client Router Cache. Backend checks remain the authorization authority for every protected read.

## Consequences and alternatives

M1 supports **one long-lived Node process only**, explicitly configured as `SESSION_MODE=single-process`. Restart fails closed and requires login. No multi-replica/serverless/cluster guarantee is claimed. Memory is bounded, expired entries pruned, and capacity returns 503. Replaced/lost local sessions do not assert global backend revocation. Shared durable sessions plus a distributed credential lock are required before scaling; adding Redis/PostgreSQL frontend state now would expand the agreed infrastructure boundary.

Browser-held access tokens plus Web Locks/BroadcastChannel were deferred: all current/future server callers would still need a single rotation owner and CSRF credential persistence. Encrypted credential cookies alone cannot prevent stale simultaneous rotations. Sticky routing alone cannot make failover/restarts safe. No alternative is allowed to retry an uncertain consumed credential.

The typed client generates from frozen OpenAPI, parses int64 losslessly and normalizes quota arithmetic to bigint. Ordinary UI data comes from Server Components, so a client server-state cache is unnecessary in M1.

Tests separate isolated BFF race/error coverage, real Spring/PostgreSQL contract/session checks and local Chrome layout/accessibility checks. Local HTTP tests with explicit Origin/session bootstrap are not browser HTTPS authentication proof. The existing HTTPS/CORS/clock/multipart release gate remains unresolved. See [frontend architecture and commands](../../frontend/README.md).

## Frontend M3 streaming extension

M3 adds only documented transfer control/status, empty creation/recovery and native
download routes to the BFF allowlist. Multipart bytes go directly to HTTPS Garage
with immutable signed checksum/length capabilities and no API credentials. All
control mutations retain exact-origin/custom-header admission and no automatic
retry. The server-held credential/rotation model is unchanged.

Download authorization and upstream header acquisition remain under the per-session
lock. The response body streams after releasing that lock, so a slow download
does not hold the credential coordinator for its entire lifetime. Client abort
and a separate stream deadline reach the upstream request; the metadata header
deadline does not truncate a valid long stream. Current backend authorization is
still checked for each new download; already admitted streams can finish after
revocation. Native download UI reports browser ownership rather than fabricating
completion on a link click. See [transfer limits and verification](../frontend-m3-transfers.md).
