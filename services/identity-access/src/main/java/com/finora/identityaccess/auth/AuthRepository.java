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
            OffsetDateTime absoluteExpiresAt, OffsetDateTime revokedAt, String userStatus) {
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
            OffsetDateTime absoluteExpiresAt) {
        jdbc.sql("""
                insert into sessions (id, user_id, token_hash, csrf_token, created_at, last_seen_at, absolute_expires_at)
                values (:id, :uid, :th, :csrf, :now, :now, :abs)
                """)
                .param("id", id).param("uid", userId).param("th", tokenHash).param("csrf", csrf).param("now", now)
                .param("abs", absoluteExpiresAt).update();
    }

    Optional<SessionRow> findSessionByHash(byte[] tokenHash) {
        return jdbc.sql("""
                select s.id, s.user_id, s.csrf_token, s.last_seen_at, s.absolute_expires_at, s.revoked_at, u.status
                from sessions s join users u on u.id = s.user_id where s.token_hash = :th
                """)
                .param("th", tokenHash).query(this::mapSession).optional();
    }

    Optional<SessionRow> findSessionById(UUID id) {
        return jdbc.sql("""
                select s.id, s.user_id, s.csrf_token, s.last_seen_at, s.absolute_expires_at, s.revoked_at, u.status
                from sessions s join users u on u.id = s.user_id where s.id = :id
                """)
                .param("id", id).query(this::mapSession).optional();
    }

    private SessionRow mapSession(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                rs.getObject(6, OffsetDateTime.class), rs.getString(7));
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

    // ---- events ----

    void insertEvent(String type, UUID userId, String requestId, OffsetDateTime now) {
        jdbc.sql("insert into auth_events (id, event_type, user_id, request_id, created_at) values (:id, :t, :uid, :rid, :now)")
                .param("id", UUID.randomUUID()).param("t", type).param("uid", userId).param("rid", requestId)
                .param("now", now).update();
    }
}
