package com.finora.finance.subscriptions;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.insights.InsightsContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a workspace may use, decided on the server from its subscription row (never from anything the client sends). A workspace with no row is on the
 * default provisional plan and ACTIVE, which keeps every existing workspace exactly as it was. Only premium features are gated (assistant, alerts, GST,
 * document uploads); the core finance workflows and every stored record stay available and readable in every state, so no subscription state ever hides,
 * blocks reading of, or deletes financial data.
 */
@Component
public class Entitlements {

    public static final String DEFAULT_PLAN = "standard";
    public static final List<String> KEYS = List.of("assistant", "alerts", "gst", "documents");
    private static final java.util.Map<String, String> LABEL = java.util.Map.of("assistant", "the finance assistant", "alerts", "alert evaluation", "gst", "GST support", "documents", "document uploads");

    public record Effective(String key, boolean enabled, Long limit, String reason) {
    }

    private record Sub(String planKey, String planName, String status) {
    }

    private final JdbcClient jdbc;

    public Entitlements(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Sub subscription(UUID tenant) {
        return jdbc.sql("""
                select s.plan_key, p.name as plan_name, s.status from workspace_subscriptions s join subscription_plans p on p.plan_key = s.plan_key where s.tenant_id = :t
                """).param("t", tenant).query(Sub.class).optional().orElseGet(() -> new Sub(DEFAULT_PLAN,
                jdbc.sql("select name from subscription_plans where plan_key = :k").param("k", DEFAULT_PLAN).query(String.class).single(), "ACTIVE"));
    }

    public Effective effective(UUID tenant, String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown entitlement");
        }
        Sub s = subscription(tenant);
        if (!"ACTIVE".equals(s.status())) {
            return new Effective(key, false, null, "Your subscription is " + s.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ", so " + LABEL.get(key)
                    + " is unavailable. Your financial data is not affected.");
        }
        return jdbc.sql("select enabled, limit_value from plan_entitlements where plan_key = :p and entitlement_key = :k").param("p", s.planKey()).param("k", key)
                .query((rs, i) -> {
                    boolean enabled = rs.getBoolean(1);
                    long limit = rs.getLong(2);
                    return new Effective(key, enabled, rs.wasNull() ? null : limit, enabled ? null : "Your current plan (" + s.planName() + ") does not include " + LABEL.get(key) + ".");
                }).optional().orElse(new Effective(key, false, null, "Your current plan (" + s.planName() + ") does not include " + LABEL.get(key) + "."));
    }

    /** Throws 403 with a clear reason unless the workspace's plan and subscription state include the feature. */
    public void require(InsightsContext ctx, String key) {
        Effective e = effective(ctx.tenantId(), key);
        if (!e.enabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN, e.reason());
        }
    }

    /** For a counted feature: the current count must be below the plan's limit (no limit means unlimited). */
    public void requireWithinLimit(InsightsContext ctx, String key, long current) {
        Effective e = effective(ctx.tenantId(), key);
        if (!e.enabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN, e.reason());
        }
        if (e.limit() != null && current >= e.limit()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Your current plan allows up to " + e.limit() + " " + (key.equals("documents") ? "documents" : key) + ". Existing records are unaffected.");
        }
    }
}
