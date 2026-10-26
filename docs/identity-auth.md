# Identity and authentication (Step 15)

Owner: `identity-access`. Edge: `api-gateway`. Decisions below were taken as documented, reversible defaults under the
owner's instruction to resolve routine details from the requirements and security best practice; they are recorded as
OA-27 in the decision register. Items marked **open** still need the owner.

## Session model (PD-06 / ADR-005 default: first-party server-side opaque sessions)
- Browser holds one cookie: `__Host-finora_sid` (`finora_sid` only when `FINORA_COOKIE_SECURE=false` for plain-HTTP dev).
  Attributes: `HttpOnly; Secure; SameSite=Lax; Path=/`, no `Domain`, `Max-Age` = absolute lifetime.
- Value = 256-bit random token. The database stores only its SHA-256 (`sessions.token_hash`). The token is never logged,
  never in a URL, never forwarded to any service other than identity-access, and never sent in a response body.
- Lifetimes: **30 min idle** (`FINORA_AUTH_IDLE_TIMEOUT`), **12 h absolute** (`FINORA_AUTH_ABSOLUTE_TIMEOUT`).
  `last_seen_at` is refreshed at most once per `touch-interval` (60 s).
- Rotation: login always issues a new session and revokes the one presented (`ROTATED`); password change and reset revoke
  **all** sessions of the user. Logout revokes (`LOGOUT`); expiry marks `EXPIRED`. A disabled user's sessions stop working.
- Validation: the gateway calls `POST /internal/v1/sessions/validate` on every request that carries the cookie, with its
  own service token. No cache, so revocation takes effect on the next request. If identity-access cannot answer, the
  request gets 503 (never treated as authenticated or as logged out).

## CSRF and origin
- Each session has a random CSRF token (returned at login and by `GET /v1/me`). State-changing requests on
  session-required routes must send it as `X-CSRF-Token`; the gateway checks it through the validation call
  (constant-time compare) and answers 403 `CSRF_INVALID`.
- Any state-changing request with an `Origin` header outside `finora.gateway.allowed-origins` (default
  `http://localhost:3000`; env `FINORA_ALLOWED_ORIGINS`) is refused, including login.
- Public credential routes (register, login, password reset) need no CSRF token (no session exists yet) but are covered by the Origin check and SameSite.

## Passwords
- Argon2id (Spring Security `Argon2PasswordEncoder`, BouncyCastle 1.86): 19 MiB memory, 2 iterations, 1 lane, 16-byte salt.
- Policy: 12–128 characters, small deny-list of very common passwords, not too repetitive, must not contain the email name.
  A breached-password corpus check is **not** implemented (needs an external data source).
- Unknown accounts still cost one Argon2 verification (timing parity).

## Login throttling
- Failures are counted per hash of the normalized email, **whether or not the account exists**. 5 failures lock the key for
  30 s, doubling on further failures up to 15 min; counter resets after 1 h without failures or on success.
  Locked attempts get 429 with `Retry-After` (even for the correct password). Password change is throttled by the same counter.
- The gateway additionally applies its strict per-IP bucket to credential routes (X-Forwarded-For is ignored).

## Password reset
- `POST /v1/auth/password-reset/request` answers 202 identically for known and unknown emails. Token: 256-bit random,
  stored as SHA-256, valid 30 min, single use (atomic `UPDATE ... WHERE used_at IS NULL`), a new request invalidates older
  tokens, repeat requests within 60 s send nothing. Confirming revokes every session.
- **Delivery is BLOCKED:** no email provider is approved or configured. `UnconfiguredPasswordResetNotifier` (default)
  creates the token, sends nothing and logs a warning without the token. A provider is added by supplying a
  `PasswordResetNotifier` bean. Until then password reset cannot work for real users.

## Not implemented (and why)
- **MFA (DEC-20 / TOTP):** not approved; deferred. Sessions carry no `amr` claim yet.
- **Email verification:** needs a mail provider. Consequence: registration reports duplicate emails (409), which allows
  account enumeration on that one endpoint (rate-limited by the gateway), and `email_verified` is always false.
- Authorization/workspace/membership (PD-07, Step 16): a valid session carries user + session only; every workspace path
  returns 404 until memberships exist.
- Audit architecture (PD-15): `auth_events` is a local security event log (no emails, passwords or tokens), not the audit design.

## Tables (migration `V3__authentication.sql`, identity-access only)
`users`, `sessions`, `password_reset_tokens`, `auth_throttle`, `auth_events`; unique/check/foreign-key constraints and
partial indexes as in the migration.
