package com.finora.identityaccess.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for identity data. Every query is parameterized. */
@Repository
class AuthRepository {

    record UserRow(UUID id, String email, String displayName, String passwordHash, String status,
            OffsetDateTime emailVerifiedAt, OffsetDateTime createdAt) {
    }

    record SessionRow(UUID id, UUID userId, String csrfToken, OffsetDateTime lastSeenAt,
            OffsetDateTime absoluteExpiresAt, OffsetDateTime revokedAt, String userStatus, boolean mfaPending,
            OffsetDateTime mfaVerifiedAt, OffsetDateTime stepUpUntil) {
    }

    record MembershipRow(UUID workspaceId, UUID membershipId, String role, String workspaceType) {
    }

    record ThrottleRow(int failureCount, OffsetDateTime lastFailureAt, OffsetDateTime lockedUntil) {
    }

    private final JdbcClient jdbc;

    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- users ----

    void insertUser(UUID id, String email, String normalized, String displayName, String passwordHash,
            OffsetDateTime now) {
        jdbc.sql("""
                insert into users (id, email, email_normalized, display_name, password_hash, password_changed_at, created_at, updated_at)
                values (:id, :email, :norm, :name, :hash, :now, :now, :now)
                """)
                .param("id", id).param("email", email).param("norm", normalized).param("name", displayName)
                .param("hash", passwordHash).param("now", now).update();
    }

    Optional<UserRow> findUserByEmail(String normalized) {
        return jdbc.sql("select id, email, display_name, password_hash, status, email_verified_at, created_at from users where email_normalized = :e")
                .param("e", normalized).query(this::mapUser).optional();
    }

    Optional<UserRow> findUserById(UUID id) {
        return jdbc.sql("select id, email, display_name, password_hash, status, email_verified_at, created_at from users where id = :id")
                .param("id", id).query(this::mapUser).optional();
    }

    private UserRow mapUser(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class));
    }

    void updatePassword(UUID userId, String hash, OffsetDateTime now) {
        jdbc.sql("update users set password_hash = :h, password_changed_at = :now, updated_at = :now where id = :id")
                .param("h", hash).param("now", now).param("id", userId).update();
    }

    // ---- sessions ----

    void insertSession(UUID id, UUID userId, byte[] tokenHash, String csrf, OffsetDateTime now,
            OffsetDateTime absoluteExpiresAt, boolean mfaPending, OffsetDateTime mfaVerifiedAt) {
        jdbc.sql("""
                insert into sessions (id, user_id, token_hash, csrf_token, created_at, last_seen_at, absolute_expires_at,
                                      mfa_pending, mfa_verified_at)
                values (:id, :uid, :th, :csrf, :now, :now, :abs, :pending, :verified)
                """)
                .param("id", id).param("uid", userId).param("th", tokenHash).param("csrf", csrf).param("now", now)
                .param("abs", absoluteExpiresAt).param("pending", mfaPending).param("verified", mfaVerifiedAt).update();
    }

    Optional<SessionRow> findSessionByHash(byte[] tokenHash) {
        return jdbc.sql("""
                select s.id, s.user_id, s.csrf_token, s.last_seen_at, s.absolute_expires_at, s.revoked_at, u.status,
                       s.mfa_pending, s.mfa_verified_at, s.step_up_until
                from sessions s join users u on u.id = s.user_id where s.token_hash = :th
                """)
                .param("th", tokenHash).query(this::mapSession).optional();
    }

    Optional<SessionRow> findSessionById(UUID id) {
        return jdbc.sql("""
                select s.id, s.user_id, s.csrf_token, s.last_seen_at, s.absolute_expires_at, s.revoked_at, u.status,
                       s.mfa_pending, s.mfa_verified_at, s.step_up_until
                from sessions s join users u on u.id = s.user_id where s.id = :id
                """)
                .param("id", id).query(this::mapSession).optional();
    }

    private SessionRow mapSession(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getBoolean(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class));
    }

    void touchSession(UUID id, OffsetDateTime now) {
        jdbc.sql("update sessions set last_seen_at = :now where id = :id and revoked_at is null")
                .param("now", now).param("id", id).update();
    }

    int revokeSession(UUID id, String reason, OffsetDateTime now) {
        return jdbc.sql("update sessions set revoked_at = :now, revoke_reason = :r where id = :id and revoked_at is null")
                .param("now", now).param("r", reason).param("id", id).update();
    }

    int revokeAllSessions(UUID userId, String reason, OffsetDateTime now) {
        return jdbc.sql("update sessions set revoked_at = :now, revoke_reason = :r where user_id = :uid and revoked_at is null")
                .param("now", now).param("r", reason).param("uid", userId).update();
    }

    void setStepUp(UUID sessionId, OffsetDateTime until) {
        jdbc.sql("update sessions set step_up_until = :u where id = :id and revoked_at is null")
                .param("u", until).param("id", sessionId).update();
    }

    boolean hasConfirmedMfa(UUID userId) {
        return jdbc.sql("select count(*) from mfa_factors where user_id = :u and confirmed_at is not null")
                .param("u", userId).query(Long.class).single() > 0;
    }

    /** Active memberships in active workspaces: what the gateway may mint workspace-scoped tokens for. */
    java.util.List<MembershipRow> activeMemberships(UUID userId) {
        return jdbc.sql("""
                select m.workspace_id, m.id, m.role, w.type from memberships m join workspaces w on w.id = m.workspace_id
                where m.user_id = :u and m.status = 'ACTIVE' and w.status = 'ACTIVE' order by m.created_at
                """)
                .param("u", userId)
                .query((rs, n) -> new MembershipRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
                .list();
    }

    void insertPersonalWorkspace(UUID workspaceId, UUID membershipId, UUID userId, OffsetDateTime now) {
        jdbc.sql("insert into workspaces (id, name, type, status, created_by, created_at, updated_at) values (:w, 'Personal', 'PERSONAL', 'ACTIVE', :u, :now, :now)")
                .param("w", workspaceId).param("u", userId).param("now", now).update();
        jdbc.sql("insert into memberships (id, workspace_id, user_id, role, status, created_at, updated_at) values (:m, :w, :u, 'OWNER', 'ACTIVE', :now, :now)")
                .param("m", membershipId).param("w", workspaceId).param("u", userId).param("now", now).update();
    }

    void updateDisplayName(UUID userId, String name, OffsetDateTime now) {
        jdbc.sql("update users set display_name = :n, updated_at = :now where id = :id")
                .param("n", name).param("now", now).param("id", userId).update();
    }

    // ---- throttle ----

    void ensureThrottleRow(String key, OffsetDateTime now) {
        jdbc.sql("insert into auth_throttle (key_hash, failure_count, last_failure_at) values (:k, 0, :now) on conflict (key_hash) do nothing")
                .param("k", key).param("now", now).update();
    }

    Optional<ThrottleRow> lockThrottleRow(String key) {
        return jdbc.sql("select failure_count, last_failure_at, locked_until from auth_throttle where key_hash = :k for update")
                .param("k", key)
                .query((rs, n) -> new ThrottleRow(rs.getInt(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class)))
                .optional();
    }

    Optional<ThrottleRow> readThrottle(String key) {
        return jdbc.sql("select failure_count, last_failure_at, locked_until from auth_throttle where key_hash = :k")
                .param("k", key)
                .query((rs, n) -> new ThrottleRow(rs.getInt(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class)))
                .optional();
    }

    void saveThrottle(String key, int count, OffsetDateTime lastFailure, OffsetDateTime lockedUntil) {
        jdbc.sql("update auth_throttle set failure_count = :c, last_failure_at = :lf, locked_until = :lu where key_hash = :k")
                .param("c", count).param("lf", lastFailure).param("lu", lockedUntil).param("k", key).update();
    }

    void deleteThrottle(String key) {
        jdbc.sql("delete from auth_throttle where key_hash = :k").param("k", key).update();
    }

    // ---- MFA ----

    record FactorRow(byte[] secretCiphertext, OffsetDateTime confirmedAt, long lastUsedStep) {
    }

    void upsertUnconfirmedFactor(UUID userId, byte[] cipher, OffsetDateTime now) {
        jdbc.sql("""
                insert into mfa_factors (user_id, secret_ciphertext, created_at) values (:u, :c, :now)
                on conflict (user_id) do update set secret_ciphertext = :c, created_at = :now, last_used_step = 0
                where mfa_factors.confirmed_at is null
                """)
                .param("u", userId).param("c", cipher).param("now", now).update();
    }

    Optional<FactorRow> findFactor(UUID userId) {
        return jdbc.sql("select secret_ciphertext, confirmed_at, last_used_step from mfa_factors where user_id = :u")
                .param("u", userId)
                .query((rs, n) -> new FactorRow(rs.getBytes(1), rs.getObject(2, OffsetDateTime.class), rs.getLong(3)))
                .optional();
    }

    void confirmFactor(UUID userId, long step, OffsetDateTime now) {
        jdbc.sql("update mfa_factors set confirmed_at = :now, last_used_step = :s where user_id = :u")
                .param("now", now).param("s", step).param("u", userId).update();
    }

    /** Atomic replay protection: a time step can be used once. */
    boolean advanceLastUsedStep(UUID userId, long step) {
        return jdbc.sql("update mfa_factors set last_used_step = :s where user_id = :u and last_used_step < :s")
                .param("s", step).param("u", userId).update() == 1;
    }

    void deleteFactor(UUID userId) {
        jdbc.sql("delete from mfa_factors where user_id = :u").param("u", userId).update();
        jdbc.sql("delete from mfa_recovery_codes where user_id = :u").param("u", userId).update();
    }

    void insertRecoveryCode(UUID userId, byte[] hash, OffsetDateTime now) {
        jdbc.sql("insert into mfa_recovery_codes (id, user_id, code_hash, created_at) values (:id, :u, :h, :now)")
                .param("id", UUID.randomUUID()).param("u", userId).param("h", hash).param("now", now).update();
    }

    void deleteRecoveryCodes(UUID userId) {
        jdbc.sql("delete from mfa_recovery_codes where user_id = :u").param("u", userId).update();
    }

    boolean consumeRecoveryCode(UUID userId, byte[] hash, OffsetDateTime now) {
        return jdbc.sql("update mfa_recovery_codes set used_at = :now where user_id = :u and code_hash = :h and used_at is null")
                .param("now", now).param("u", userId).param("h", hash).update() == 1;
    }

    boolean hasPrivilegedBusinessRole(UUID userId) {
        return jdbc.sql("""
                select count(*) from memberships m join workspaces w on w.id = m.workspace_id
                where m.user_id = :u and m.status = 'ACTIVE' and w.type = 'BUSINESS' and m.role in ('OWNER', 'ADMIN')
                """).param("u", userId).query(Long.class).single() > 0;
    }

    // ---- password reset ----

    void insertResetToken(UUID id, UUID userId, byte[] hash, OffsetDateTime now, OffsetDateTime expires) {
        jdbc.sql("insert into password_reset_tokens (id, user_id, token_hash, created_at, expires_at) values (:id, :uid, :h, :now, :exp)")
                .param("id", id).param("uid", userId).param("h", hash).param("now", now).param("exp", expires).update();
    }

    void invalidateUnusedResetTokens(UUID userId, OffsetDateTime now) {
        jdbc.sql("update password_reset_tokens set used_at = :now where user_id = :uid and used_at is null")
                .param("now", now).param("uid", userId).update();
    }

    Optional<OffsetDateTime> latestResetTokenCreatedAt(UUID userId) {
        return jdbc.sql("select max(created_at) from password_reset_tokens where user_id = :uid")
                .param("uid", userId).query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).optional()
                .filter(java.util.Objects::nonNull);
    }

    Optional<UUID> findUsableResetToken(byte[] hash, OffsetDateTime now) {
        return jdbc.sql("select user_id from password_reset_tokens where token_hash = :h and used_at is null and expires_at > :now")
                .param("h", hash).param("now", now).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    /** Atomic single use: exactly one caller can win the update. */
    Optional<UUID> consumeResetToken(byte[] hash, OffsetDateTime now) {
        return jdbc.sql("update password_reset_tokens set used_at = :now where token_hash = :h and used_at is null and expires_at > :now returning user_id")
                .param("h", hash).param("now", now).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    // ---- security audit log (append-only; PD-15 architecture still pending) ----

    void insertAudit(UUID workspaceId, UUID actor, String type, String targetType, String targetId, String outcome,
            String detail, String requestId, OffsetDateTime now) {
        jdbc.sql("""
                insert into security_audit_events (id, workspace_id, actor_user_id, event_type, target_type, target_id, outcome, detail, request_id, created_at)
                values (:id, :w, :a, :t, :tt, :ti, :o, :d, :r, :now)
                """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("a", actor).param("t", type)
                .param("tt", targetType).param("ti", targetId).param("o", outcome).param("d", detail)
                .param("r", requestId).param("now", now).update();
    }

    // ---- events ----

    void insertEvent(String type, UUID userId, String requestId, OffsetDateTime now) {
        jdbc.sql("insert into auth_events (id, event_type, user_id, request_id, created_at) values (:id, :t, :uid, :rid, :now)")
                .param("id", UUID.randomUUID()).param("t", type).param("uid", userId).param("rid", requestId)
                .param("now", now).update();
    }
}
