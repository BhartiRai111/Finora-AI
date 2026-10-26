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

---

# Workspaces, roles and access control (Step 16)

Owner: `identity-access` (workspaces, memberships, invitations, MFA, security audit log). Permission catalogue:
`libs/service-commons` (`com.finora.commons.authz`). Defaults below were taken under OA-27/OA-28 and are reversible; **open** items need the owner.

## Model
- **Personal profile** = a `PERSONAL` workspace created at registration (backfilled by V4 for existing accounts): its own tenant,
  one per user, the user is sole Owner, it cannot be invited into, renamed or transferred. Business workspaces are `BUSINESS`.
  User identity (`users`) holds no workspace data; access exists only through `memberships`.
- **Roles** (PRD 4.1): Owner, Admin, Accountant, Manager, Contributor, Auditor, Viewer. Approval authority is not a role.
- **Membership states:** `ACTIVE`, `SUSPENDED`, `REMOVED` (history kept). Invitation states: `PENDING`, `ACCEPTED`, `DECLINED`, `REVOKED`, `EXPIRED`.
- **Permission matrix** = PRD 4.2 encoded in `PermissionMatrix` (default deny). `PermissionMatrixTest` compares every cell with a verbatim copy
  of the PRD table. Finance/document/etc. services will reuse the same catalogue; object-level scopes (`OWN`, `ASSIGNED`, `IF_GRANTED`) are
  returned for the owning service to apply.

## Rules implemented (DEC-15 defaults; PERM-5..7, PERM-10)
- Owner may grant/change/remove any role; **Admin only up to Accountant level** (Accountant, Manager, Contributor, Auditor, Viewer) and only
  over members at those levels. No self role change. Role changes, ownership transfer need **step-up** (password + current second factor if enrolled, valid 10 min).
- **Last Owner protected:** every membership change takes a row lock on the workspace and re-reads the caller's role under it; concurrency tests show
  a workspace never reaches zero Owners.
- **Ownership transfer** (Owner only, step-up): the new Owner is promoted before the old one becomes Admin; the new Owner must have MFA enabled.
  Workspace deletion and data deletion are **not implemented** (they need cross-service deletion orchestration and legal rules, DEC-17/RET-8).
- **Invitations:** 7-day expiry, random 256-bit token stored as SHA-256, bound to the invitee's email, fixed role, single use (atomic update),
  concurrent accepts create exactly one membership, every failure returns the same neutral error, every attempt is audited without the token.
  The token only travels in the link fragment (`#token=`), which browsers do not send to servers or logs.
- **Isolation:** the caller's identity comes only from the verified token; the workspace in the URL is a request. identity-access re-reads the live
  session and membership from the database on every call (a removed member loses access on the next request even with a still-valid token). A foreign
  workspace id behaves exactly like a missing one (404, identical body). Membership/invitation ids are always matched together with the workspace id.
- **Gateway:** `/internal/v1/sessions/validate` now returns the user's ACTIVE memberships; the gateway mints a workspace-scoped token (tid, mid, role,
  step-up) only for a workspace the session belongs to, otherwise 404 without forwarding. Sessions waiting for MFA can reach only MFA verification and logout.

## Mandatory MFA for Owner/Admin (DEC-20 default: yes) and MFA (AUTH-4)
- TOTP (RFC 6238, SHA-1, 6 digits, 30 s, +/-1 step) + 10 single-use recovery codes. Secret AES-256-GCM encrypted (key file, bound to the user id);
  recovery codes SHA-256 hashed; a time step cannot be reused; failures are throttled; enabling MFA and verifying a login rotate the session.
- A user with a confirmed factor logs in to a **pending** session that can do nothing but verify or log out.
- **Enforcement:** any Owner/Admin operation in a workspace needs a session that completed MFA (`MFA_REQUIRED` otherwise). Owner/Admin roles may be given
  only to members who already enabled MFA. MFA cannot be disabled while holding Owner/Admin in a business workspace.
- **Needs configuration:** `FINORA_MFA_KEY_PATH` (base64 256-bit key; `tools/gen-dev-keys.sh` creates one). Without it MFA reports itself unavailable
  and Owners/Admins cannot administer workspaces (fails closed).
- **Not covered / open:** workspace-level "enforce MFA for more roles" switch, WebAuthn, SMS, MFA reset by support (DEC-17-type process), SHA-1 is
  the authenticator-app interoperability default.

## Invitation delivery (no mail provider)
`InvitationNotifier` is provider-neutral. Default: nothing is sent and the API/UI say `NOT_CONFIGURED`. Development-only adapter: set
`FINORA_INVITATIONS_DEV_OUTBOX_FILE` to append links to a local file (`DEV_CAPTURED`, nothing is emailed). Never claim real delivery without a provider.

## Audit (PD-15 still pending)
There is **no approved audit mechanism**. `security_audit_events` is a local, append-only table in identity-access (runtime role has no UPDATE/DELETE/TRUNCATE;
tested) written in the same transaction as each change, plus separate rows for denied attempts. It is not the central, hash-chained audit service and
makes no tamper-evidence or compliance claim. Reading the audit log is not exposed (belongs to the audit service).

## Frontend
Next.js screens: register, login (+ two-step), workspaces list/create/switcher, workspace overview/members/invitations/settings, invitation acceptance,
account (profile, MFA setup with recovery codes, password change). The browser talks only to `/api/gw/*` (same origin, proxied to the gateway), holds no
secret except an in-memory CSRF token, and shows explicit loading/error/empty states. UI permission checks are hints only; the server decides.
