# Authentication sessions, logout and profile

## Contract defined before implementation

The separate [browser multipart harness](browser-multipart-interoperability.md)
now consumes this unchanged same-origin HTTPS contract. It includes client/CSRF
admission checks, cookie rotation probes, one-flight refresh plus Web Locks and
reauthentication after uncertain refresh. No backend cross-origin auth policy or
cookie relaxation is introduced; actual browser cookie behavior remains unverified.

Access tokens remain HS256 Authorization bearer JWTs with UUID subjects and now
carry unique UUID `jti` claims. Login/refresh tokens also carry a session UUID
`sid`. Access lifetime is 30 minutes; refresh families have a fixed seven-day
absolute lifetime (rotation does not extend it).
The same seven-day value controls database creation and the initial cookie's
604800-second Max-Age. Rotated cookies receive only the family's remaining time.
Refresh eligibility also caps older families at created_at plus seven days, even
if their stored expiry is later; no existing expiration is extended or backfilled.
This bound is fixed by `SessionService.REFRESH_SECONDS`; no refresh-lifetime
configuration override can increase it. The existing 30-minute access setting is unchanged.

Browser refresh transport is only the `__Host-vaultdrive-refresh` cookie:
Secure, HttpOnly, SameSite=Strict, Path=/, no Domain. HTTPS is required. Raw
refresh credentials never enter JSON, query strings or persistence. Login and
refresh retain `{accessToken, tokenType, expiresIn}` JSON and return a separate
`X-VaultDrive-CSRF` response header. Clients retain this random synchronizer
secret and send it on refresh/logout; its SHA-256 hash is session-bound in
PostgreSQL. Login requires `X-VaultDrive-Client: 1` to prevent login CSRF through
cross-origin simple requests. No credentialed cross-origin CORS policy is enabled.
All three endpoints reject query strings. Auth and profile responses use no-store.

Refresh requires cookie + CSRF header, without an access JWT. Logout requires a
valid access JWT plus cookie + CSRF matching that JWT's session and user. Logout
atomically revokes that family and inserts the presented JWT's `jti` revocation;
only committed success returns 204 and clears the cookie. Refresh with missing,
invalid, expired, revoked or replayed credentials returns a generic 401. Logout
can use a consumed or expired retained refresh hash to revoke its matching family
while the presented JWT is still valid; a mismatched family/user returns 401.
Missing/malformed CSRF headers return 403; incorrect session-bound CSRF returns
403 without revocation.
An already revoked access JWT receives 401, including repeated logout. Database
failure never produces a successful refresh/logout; no cookie is cleared on failure.

Each session is one token family. Lookup a hash to discover its family, lock the
family row FOR UPDATE, then reread the token under that lock. Rotation consumes
the old hash and inserts one new hash in the same transaction. Retain consumed
hashes through family expiry for reuse detection. A correctly CSRF-bound consumed
token revokes only its own family, committing that change before returning 401.
Concurrent use therefore allows at most one rotation, followed by family
revocation; clients must serialize refresh requests. Response loss followed by
retry has the same strict replay outcome; no grace window is provided.

Every authenticated JWT request checks indexed PostgreSQL jti revocation and,
when present, session revocation. Missing jti, malformed identities, missing
sessions and unavailable database state fail closed. Session revocation also
invalidates that family's outstanding access JWTs. In-flight requests admitted
before revocation commits may finish. Other sessions remain valid.

Revocation expiry includes the decoder's 60-second timestamp tolerance. Bounded
explicit cleanup deletes only expired jti rows; no autonomous scheduler or
refresh-history deletion is introduced. No Redis dependency is needed.

Profile GET returns userId, email, displayName, createdAt and updatedAt only.
PATCH accepts a nonblank displayName of at most 100 characters without control
characters, trims it and returns the same DTO. Identity always comes from the authenticated subject.
Password hashes and authentication secrets never enter profile responses.

Rotation rationale follows [RFC 9700 section 4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14).
JWT validation extends [Spring Security resource-server validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## API reference

| Endpoint | Request | Success |
|---|---|---|
| POST `/api/v1/auth/register` | Existing email/password/displayName JSON | Existing 201 registration response |
| POST `/api/v1/auth/login` | Existing email/password JSON; `X-VaultDrive-Client: 1` | 200 access-token JSON + refresh Set-Cookie + CSRF response header |
| POST `/api/v1/auth/refresh` | Exactly one refresh cookie and `X-VaultDrive-CSRF`; empty body | 200 same JSON shape + rotated Set-Cookie + same CSRF header |
| POST `/api/v1/auth/logout` | Bearer JWT, matching refresh cookie and `X-VaultDrive-CSRF`; empty body | 204; expired refresh Set-Cookie |
| GET `/api/v1/users/me` | Bearer JWT | 200 `{userId,email,displayName,createdAt,updatedAt}` |
| PATCH `/api/v1/users/me` | Bearer JWT; `{"displayName":"New name"}` | 200 same profile shape; 400 for absent/null/blank/overlong/control-character names |

Login/refresh JSON remains `{accessToken,tokenType:"Bearer",expiresIn:1800}` with
the default configuration. Access lifetime remains configurable through the
existing property. Profile rejects unknown users with 401; extra submitted
identity/credential fields have no update authority. Cookie credentials alone
cannot authorize profile or any ordinary API. Logout without a valid JWT returns
401; it is deliberately not a refresh-only logout endpoint. Malformed duplicate
refresh cookies return 401. Invalid credential errors retain the existing generic
login response. Session failures use a generic ApiError; missing/malformed CSRF
admission and forbidden query strings have empty 403/400 responses. Auth-state
transaction/database failures return 503; JWT admission failures return 401.
Failures issue no replacement or clearing cookie. There is no global-logout API.
Connection loss during commit can leave an uncertain outcome: a 503 does not
prove rollback. A committed-but-unobserved rotation followed by retry invokes the
same strict reuse policy; a committed logout leaves the old credentials denied.
Reauthentication is the recovery path. No distributed transaction is involved.
Omit Authorization on refresh requests: the resource server rejects an invalid or
expired bearer header even on public routes, preserving its existing behavior.

Clients must keep their synchronizer secret separately from the HttpOnly cookie
(for example in tab session storage), serialize refresh calls, discard old access
tokens after refresh and reauthenticate after replay/lost-response failure. Same
origin deployment is the supported browser contract; cross-site cookie/CORS
configuration and CSRF recovery after loss of the client secret are not supplied.

## Rollout and operations

Apply append-only V37 before serving new application instances. It adds only auth
tables/indexes; previous migrations, password hashes and resource permissions are
unchanged. Old JWTs without jti are rejected: users log in again on rollout. Do not
run old auth instances after rollout: their decoder would bypass revocation and
their login would omit refresh/CSRF credentials. Coordinate the replacement of all
instances. New instances share PostgreSQL state and signing configuration.

Serve HTTPS and keep the cookie flags fixed, including in development browser
testing. Reverse proxies/APM/access logs must redact Authorization, Cookie,
Set-Cookie and X-VaultDrive-CSRF headers and login bodies. Do not enable JDBC bind
logging for credential tables. Never put credentials in URLs; endpoint query
rejection cannot remove a URL already recorded by upstream access logging.

Session transactions use three-second lock timeouts and five-second transaction
deadlines, with independent REQUIRES_NEW boundaries so caller rollback cannot undo
a committed reuse revocation. Revocation reads have a five-second JDBC query
timeout. An unavailable database denies JWT admission, including health requests that explicitly present
a bearer credential; anonymous health remains public. Pool/connection acquisition
timeouts remain governed by datasource configuration. No latency/throughput or
multi-node failure measurement is claimed. Backend requests admitted before a
revocation commits can finish; revocation does not recall downloads already begun.

Invoke `AccessTokenState.cleanupExpired(limit)` from a trusted operational caller,
with 1..1000 rows per invocation. It takes SKIP LOCKED expiry-ordered batches in a
five-second transaction; concurrent instances cannot delete live revocations. The
equivalent bounded PostgreSQL operation is:

```sql
BEGIN;
SET LOCAL statement_timeout = '5s';
WITH expired AS (
  SELECT jti FROM auth_access_revocations WHERE expires_at <= statement_timestamp()
  ORDER BY expires_at, jti LIMIT 1000 FOR UPDATE SKIP LOCKED
)
DELETE FROM auth_access_revocations r USING expired e WHERE r.jti = e.jti;
COMMIT;
```

No scheduler or admin HTTP endpoint is enabled. Never delete live revocations or
retained refresh hashes as a troubleshooting shortcut. Expired families and token
history remain retained; bounded family/history retention is a later milestone.
Monitor table growth and deny-rate/database availability using aggregate counters
without secret-valued labels. Time synchronization remains a deployment requirement.

## Verification

`AuthenticationSessionIntegrationTest` uses a UUID-named schema in the dedicated
`vaultdrive_test` database, real localhost HTTP and PostgreSQL transactions. Its
locks/fault-injection DDL affect only that private schema. Synthetic fixtures are
retained for inspection; no shared schema is swept. The concurrent-refresh test
holds the exact family row and observes both requests waiting before releasing it.
No in-process mutex supplies the tested single-use guarantee.
HTTP tests send cookies explicitly over loopback and assert browser cookie flags
and CORS/CSRF responses; they do not automate a browser's cookie policy or claim
multi-node failover/load verification.

Load `.env.local.ps1` in the same PowerShell process as Maven. Focused command:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=AuthenticationSessionIntegrationTest,AuthControllerTest,AuthenticationServiceTest,RegistrationServiceTest,PasswordConfigTest,JwtServiceTest,SecurityIntegrationTest,UserRepositoryTest' '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=auth_mvp_focused' '-Dspring.flyway.schemas=auth_mvp_focused' '-Dspring.flyway.default-schema=auth_mvp_focused' test
```

For full verification, omit `-Dtest`, use a separate `auth_mvp_final_verification`
schema with the same three schema properties and the existing safe Garage and
RabbitMQ gates documented in [Sharing M6 verification](sharing-m6.md#verification).
All production publisher/consumer/worker defaults remain disabled.

Original milestone verification, before the seven-day correction below, executed
on 2026-10-19 against PostgreSQL 17.11 and the inspected Garage 2.3.0 service:

- Final focused run: **50 passed**, no failures/errors/skips, including 25 new
  isolated PostgreSQL/HTTP session checks.
- Final full run on the final source: **1,384 tests; 1,363 passed, 21 skipped**,
  no failures/errors. Includes 12 isolated live RabbitMQ tests and 27 gated Garage
  multipart tests; other existing storage/authorization regressions also passed.
- An earlier full run before six additional session cases passed 1,378 tests
  with the same 21 skips. The initial focused run exposed servlet error
  redispatch replacing 403/503 with 401; the direct auth-error handler fixed it
  and both final runs passed.
- Whitespace checks and local Markdown targets passed. No old migration was
  changed and no production worker was enabled. Test-owned storage/topology
  cleanup followed the existing suites; synthetic database schemas are retained.

The following separately gated checks were not run:

| Suite | Skipped | Separate prerequisite |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` and private end-to-end runbook |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` and capability runbook |
| `GarageMultipartJournalRecoveryTest` | 1 | separately reviewed retained-run UUID |

The end-to-end harness login request was updated for the new client header; the
gated harness itself was not rerun. No browser UI, browser multipart feature,
deployment, key rotation, account-state control or general rate limiter is added.

## Seven-day lifetime correction verification

On 2026-10-19, the corrected lifetime passed **52 focused authentication tests**
and the full safely runnable suite: **1,386 tests; 1,365 passed, 21 gated skips**,
with no failures/errors. The same four separately gated suites listed above
remain skipped; live isolated RabbitMQ and gated Garage multipart checks ran.
Focused/default test contexts used `auth_7d_focused`; the full run used
`auth_7d_verification` with the three schema properties documented above. The HTTP
session tests retained their own UUID-named schemas.

Regression checks assert exactly seven days between new family creation and
expiry, initial cookie Max-Age 604800, unchanged stored expiry across repeated
rotation, shortened remaining cookie age, and rejection of older families beyond
seven days despite a later stored expiry. Existing replay, revocation, cookie/CSRF
and concurrency checks passed. No migration, lock-order, access-token lifetime or
infrastructure-default change was needed. Whitespace and local Markdown links passed.
