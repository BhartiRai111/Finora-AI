package com.finora.commons.idempotency;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exactly-once effect for retried writes. The key row is inserted in the same transaction as the work it protects:
 * <ul>
 *   <li>first call: key stored with the work, response stored, commit;</li>
 *   <li>repeat with the same request: stored response replayed, work not run;</li>
 *   <li>same key, different request: 422 {@code IDEMPOTENCY_KEY_REUSED};</li>
 *   <li>concurrent calls: PostgreSQL makes the second insert wait for the first to commit or roll back;</li>
 *   <li>work throws: whole transaction rolls back, the key is released and the caller may retry;</li>
 *   <li>after {@code ttl}: the key may be reused.</li>
 * </ul>
 * The scope separates key spaces (tenant, endpoint) so one tenant's key cannot collide with another's.
 */
public class IdempotencyService {

    public record StoredResponse(int status, String body) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(JdbcClient jdbc, TransactionTemplate tx, Clock clock, Duration ttl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.ttl = ttl;
    }

    public static String hashRequest(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code work} at most once per (scope, key); returns its response, or the stored one on a repeat. */
    public StoredResponse execute(String scope, String key, String requestHash, Supplier<StoredResponse> work) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Idempotency-Key must be 1-128 characters");
        }
        return tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.sql("delete from idempotency_keys where scope = :scope and idem_key = :key and expires_at <= :now")
                    .param("scope", scope).param("key", key).param("now", now).update();
            int inserted = jdbc.sql("""
                    insert into idempotency_keys (scope, idem_key, request_hash, created_at, expires_at)
                    values (:scope, :key, :hash, :now, :expires)
                    on conflict (scope, idem_key) do nothing
                    """)
                    .param("scope", scope).param("key", key).param("hash", requestHash)
                    .param("now", now).param("expires", now.plus(ttl)).update();
            if (inserted == 1) {
                StoredResponse response = work.get();
                jdbc.sql("update idempotency_keys set response_status = :s, response_body = :b where scope = :scope and idem_key = :key")
                        .param("s", response.status()).param("b", response.body())
                        .param("scope", scope).param("key", key).update();
                return response;
            }
            Optional<Row> existing = jdbc.sql("select request_hash, response_status, response_body from idempotency_keys where scope = :scope and idem_key = :key")
                    .param("scope", scope).param("key", key)
                    .query((rs, n) -> new Row(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)))
                    .optional();
            Row row = existing.orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this key is being processed", 1));
            if (!row.hash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was used with a different request");
            }
            if (row.status() == null) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "A request with this key is being processed", 1);
            }
            return new StoredResponse(row.status(), row.body());
        });
    }

    /** Housekeeping for a future scheduler; safe to call at any time. */
    public int purgeExpired() {
        return jdbc.sql("delete from idempotency_keys where expires_at <= :now")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }

    private record Row(String hash, Integer status, String body) {
    }
}
