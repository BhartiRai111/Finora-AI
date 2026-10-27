package com.finora.finance.accounting;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.insights.InsightsContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Accounting integration state and the reusable foundations (token vault, duplicate-event store, sync ledger). With no approved provider the
 * state is NOT_CONFIGURED and nothing can connect or sync; the pending decisions are listed instead of a fake connection.
 */
@Service
public class AccountingService {

    public static final List<String> PENDING = List.of("Which accounting provider(s) are approved (none is approved)", "The provider's API contract, scopes and rate limits",
            "OAuth client registration and redirect URIs for each environment (credentials are never invented)", "Where the token-vault key is managed in production (secrets-management decision)",
            "Webhook signing scheme and shared-secret handling of the chosen provider", "Which records may be exported or imported, and the owner's conflict policy");

    private final JdbcClient jdbc;
    private final AccountingProvider provider;
    private final TokenVault vault;

    public AccountingService(JdbcClient jdbc, AccountingProvider provider, TokenVault vault) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.vault = vault;
    }

    private static void guard(InsightsContext ctx) {
        if (!ctx.business()) {
            throw new ApiException(ErrorCode.CONFLICT, "Accounting integrations exist only in business workspaces");
        }
        ctx.businessContext().requireAll(Resource.COMPANY_SETTINGS, Action.READ);
    }

    public Map<String, Object> status(InsightsContext ctx) {
        guard(ctx);
        List<Map<String, Object>> connections = jdbc.sql("select provider, status, token_expires_at, scopes from accounting_connections where tenant_id = :t order by created_at")
                .param("t", ctx.tenantId()).query((rs, i) -> Map.<String, Object>of("provider", rs.getString(1), "status", rs.getString(2),
                        "token_expires_at", String.valueOf(rs.getObject(3, OffsetDateTime.class)), "scopes", String.valueOf(rs.getString(4)))).list();
        return Map.of("provider", Map.of("key", provider.key(), "status", provider.configured() ? "CONFIGURED" : "NOT_CONFIGURED", "approved", false),
                "message", "No accounting provider has been approved or configured. Nothing is connected, nothing is synchronised, and nothing was sent to any accounting system.",
                "capabilities", Map.of("oauth_connect", false, "webhooks", false, "sync", false), "token_vault", Map.of("available", vault.available()),
                "connections", connections, "pending_decisions", PENDING);
    }

    public List<Map<String, Object>> syncRuns(InsightsContext ctx) {
        guard(ctx);
        return jdbc.sql("select id, provider, direction, status, attempts, records_ok, records_failed, error_code, error_message, started_at, finished_at from accounting_sync_runs "
                + "where tenant_id = :t order by started_at desc limit 50").param("t", ctx.tenantId()).query((rs, i) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rs.getObject(1, UUID.class));
            m.put("provider", rs.getString(2));
            m.put("direction", rs.getString(3));
            m.put("status", rs.getString(4));
            m.put("attempts", rs.getInt(5));
            m.put("records_ok", rs.getInt(6));
            m.put("records_failed", rs.getInt(7));
            m.put("error_code", rs.getString(8));
            m.put("error_message", rs.getString(9));
            m.put("started_at", String.valueOf(rs.getObject(10, OffsetDateTime.class)));
            m.put("finished_at", String.valueOf(rs.getObject(11, OffsetDateTime.class)));
            return m;
        }).list();
    }

    // ---------------------------------------------------------------- foundations used by a future adapter (and tested directly)

    /** True only the first time an event id is seen for a provider. A repeated delivery returns false and must be acknowledged without being processed again. */
    public boolean firstDelivery(String providerKey, String eventId, UUID tenantId, byte[] payload, java.time.Clock clock) {
        String hash = java.util.HexFormat.of().formatHex(sha256(payload));
        return jdbc.sql("insert into accounting_webhook_events (provider, event_id, tenant_id, payload_hash, received_at) values (:p, :e, :t, :h, :now) on conflict do nothing")
                .param("p", providerKey).param("e", eventId).param("t", tenantId).param("h", hash).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update() == 1;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public UUID startRun(UUID tenantId, String providerKey, String direction, java.time.Clock clock) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into accounting_sync_runs (id, tenant_id, provider, direction, status, started_at) values (:id, :t, :p, :d, 'RUNNING', :now)")
                .param("id", id).param("t", tenantId).param("p", providerKey).param("d", direction).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        return id;
    }

    public void finishRun(UUID tenantId, UUID run, String status, int ok, int failed, int attempts, String errorCode, String errorMessage, java.time.Clock clock) {
        jdbc.sql("update accounting_sync_runs set status = :s, records_ok = :ok, records_failed = :f, attempts = :a, error_code = :c, error_message = :m, finished_at = :now where tenant_id = :t and id = :id")
                .param("s", status).param("ok", ok).param("f", failed).param("a", attempts).param("c", errorCode).param("m", Redaction.safe(errorMessage))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("t", tenantId).param("id", run).update();
    }
}
