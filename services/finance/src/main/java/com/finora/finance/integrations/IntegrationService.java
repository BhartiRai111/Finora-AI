package com.finora.finance.integrations;

import com.finora.finance.accounting.AccountingProvider;
import com.finora.finance.accounting.TokenVault;
import com.finora.finance.assistant.AiProvider;
import com.finora.finance.documents.OcrProvider;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.notifications.NotificationService;
import com.finora.finance.subscriptions.SubscriptionService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The integration registry of one workspace: what each external dependency is, and its REAL state read from configuration and from the existing records (accounting
 * connections and the sync-run ledger from Step 21). States: NOT_CONFIGURED (nothing approved or set), CONFIGURED (set, but no live check has been made), CONNECTED (a connection
 * is established and the latest operation did not fail), FAILED (the latest operation failed or the connection is in error/expired), UNAVAILABLE (reserved for a
 * configured dependency that cannot be reached) and DISCONNECTED (the connection was revoked). Nothing here contacts a provider, and no state is invented: today every
 * external integration is NOT_CONFIGURED. Error history comes from the existing sync ledger, whose messages are redacted before they are stored.
 */
@Service
public class IntegrationService {

    public record Integration(String key, String name, String kind, String state, String message, Map<String, Object> health, List<String> pendingDecisions) {
    }

    private record Conn(String provider, String status) {
    }

    private record Run(String status, OffsetDateTime finishedAt, String errorCode) {
    }

    private final JdbcClient jdbc;
    private final AccountingProvider accounting;
    private final TokenVault vault;
    private final AiProvider ai;
    private final OcrProvider ocr;
    private final NotificationService notifications;
    private final SubscriptionService subscriptions;

    public IntegrationService(JdbcClient jdbc, AccountingProvider accounting, TokenVault vault, AiProvider ai, OcrProvider ocr, NotificationService notifications, SubscriptionService subscriptions) {
        this.jdbc = jdbc;
        this.accounting = accounting;
        this.vault = vault;
        this.ai = ai;
        this.ocr = ocr;
        this.notifications = notifications;
        this.subscriptions = subscriptions;
    }

    public List<Integration> registry(InsightsContext ctx) {
        ctx.requireSettingsRead();
        List<Integration> out = new ArrayList<>();
        out.add(accountingEntry(ctx));
        out.add(new Integration("ai", "AI assistant provider", "AI", ai.configured() ? "CONFIGURED" : "NOT_CONFIGURED",
                ai.configured() ? ("test".equals(ai.name()) ? "The built-in test adapter is active (development and tests only; not a language model). No live check is made." : "A provider is configured. No live check is made here.")
                        : "No AI provider is approved or configured. The assistant answers from deterministic calculations without one.", Map.of("provider", ai.name(), "live_check", "not performed"),
                ai.configured() ? List.of() : List.of("AI provider and model (DL-01, AI-1..AI-5)", "Provider data-processing terms")));
        out.add(new Integration("ocr", "Document OCR engine", "OCR", ocr.configured() ? "CONFIGURED" : "NOT_CONFIGURED", ocr.configured()
                ? "A test OCR adapter is active (development and tests only; it is not real OCR)." : "No OCR engine is configured. Documents report OCR as unavailable.", Map.of("provider", ocr.name(), "live_check", "not performed"),
                ocr.configured() ? List.of() : List.of("OCR engine selection")));
        out.add(new Integration("exchange_rates", "Exchange-rate source", "FX", "NOT_CONFIGURED", "No exchange-rate provider is approved. Rates are entered by people (user-supplied).", Map.of("manual_entry", true),
                List.of("Exchange-rate source and its terms")));
        out.add(new Integration("notification_delivery", "External notification delivery (email, SMS, push, webhook)", "NOTIFICATIONS", "NOT_CONFIGURED",
                "No delivery provider is approved or configured. Notifications appear in the application only; nothing is sent outside it.", Map.of("in_app", "available"), List.of("Email/SMS/push delivery provider")));
        out.add(new Integration("payments", "Payment provider", "BILLING", "NOT_CONFIGURED", "No payment provider is approved or configured. No payment can be taken and no billing record is paid automatically.", Map.of(),
                List.of("Payment provider and commercial plan policy")));
        return out;
    }

    private Integration accountingEntry(InsightsContext ctx) {
        List<Conn> conns = jdbc.sql("select provider, status from accounting_connections where tenant_id = :t order by created_at").param("t", ctx.tenantId()).query(Conn.class).list();
        Run last = jdbc.sql("select status, finished_at, error_code from accounting_sync_runs where tenant_id = :t and finished_at is not null order by finished_at desc limit 1").param("t", ctx.tenantId()).query(Run.class).optional().orElse(null);
        OffsetDateTime lastSuccess = jdbc.sql("select max(finished_at) from accounting_sync_runs where tenant_id = :t and status = 'SUCCEEDED'").param("t", ctx.tenantId()).query(OffsetDateTime.class).optional().orElse(null);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("provider", accounting.key());
        health.put("token_vault", vault.available() ? "configured" : "not configured");
        health.put("connections", conns.size());
        health.put("last_successful_operation_at", lastSuccess == null ? null : lastSuccess.toString());
        health.put("last_operation_status", last == null ? null : last.status());
        health.put("last_error_code", last == null ? null : last.errorCode());
        String state;
        String message;
        if (conns.isEmpty()) {
            state = accounting.configured() ? "CONFIGURED" : "NOT_CONFIGURED";
            message = accounting.configured() ? "A provider is configured but nothing is connected." : "No accounting provider is approved or configured. Nothing is connected, synchronised or sent to any accounting system.";
        } else if (conns.stream().allMatch(c -> "REVOKED".equals(c.status()))) {
            state = "DISCONNECTED";
            message = "The connection was revoked.";
        } else if (conns.stream().anyMatch(c -> "ERROR".equals(c.status()) || "EXPIRED".equals(c.status())) || (last != null && ("FAILED".equals(last.status())))) {
            state = "FAILED";
            message = "The connection is in error or expired, or the latest synchronisation failed. Financial data in Finora is unaffected.";
        } else if (conns.stream().anyMatch(c -> "CONNECTED".equals(c.status()))) {
            state = "CONNECTED";
            message = "Connected; the latest operation did not fail.";
        } else {
            state = "CONFIGURED";
            message = "Waiting for consent.";
        }
        return new Integration("accounting", "Accounting system", "ACCOUNTING", state, message, health,
                accounting.configured() ? List.of() : com.finora.finance.accounting.AccountingService.PENDING);
    }

    /** Failed and partly failed synchronisations, newest first. The stored messages are already redacted. */
    public List<Map<String, Object>> errors(InsightsContext ctx, String key) {
        ctx.requireSettingsRead();
        if (!"accounting".equals(key)) {
            return List.of();
        }
        return jdbc.sql("select id, provider, direction, status, attempts, records_ok, records_failed, error_code, error_message, finished_at from accounting_sync_runs where tenant_id = :t and status in ('FAILED', 'PARTIAL') "
                + "order by started_at desc limit 50").param("t", ctx.tenantId()).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", rs.getObject(1, java.util.UUID.class));
            m.put("provider", rs.getString(2));
            m.put("direction", rs.getString(3));
            m.put("status", rs.getString(4));
            m.put("attempts", rs.getInt(5));
            m.put("records_ok", rs.getInt(6));
            m.put("records_failed", rs.getInt(7));
            m.put("error_code", rs.getString(8));
            m.put("error_message", rs.getString(9));
            m.put("finished_at", String.valueOf(rs.getObject(10, OffsetDateTime.class)));
            return m;
        }).list();
    }

    /** Workspace administration summary for Owners/Admins. This is NOT platform administration: it covers this workspace only. */
    public Map<String, Object> adminOverview(InsightsContext ctx) {
        ctx.requireSettingsRead();
        Map<String, Integer> states = new TreeMap<>();
        registry(ctx).forEach(i -> states.merge(i.state(), 1, Integer::sum));
        Map<String, Long> alerts = new TreeMap<>();
        jdbc.sql("select severity, count(*) from alerts where tenant_id = :t and status = 'OPEN' group by severity").param("t", ctx.tenantId()).query((rs, i) -> {
            alerts.put(rs.getString(1), rs.getLong(2));
            return null;
        }).list();
        long openRecords = jdbc.sql("select count(*) from billing_records where tenant_id = :t and status = 'OPEN'").param("t", ctx.tenantId()).query(Long.class).single();
        long failedRecords = jdbc.sql("select count(*) from billing_records where tenant_id = :t and status = 'PAYMENT_FAILED'").param("t", ctx.tenantId()).query(Long.class).single();
        var sub = subscriptions.current(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "WORKSPACE");
        out.put("note", "Administration of this workspace only. Platform-wide administration does not exist yet: it needs an approved staff identity and authentication model.");
        out.put("subscription", Map.of("plan", sub.plan().planKey(), "plan_name", sub.plan().name(), "status", sub.status(), "provisional", sub.plan().provisional()));
        out.put("integrations_by_state", states);
        out.put("open_alerts_by_severity", alerts);
        out.put("unread_notifications", notifications.unreadCount(ctx));
        out.put("billing", Map.of("provider_status", "NOT_CONFIGURED", "open_records", openRecords, "failed_payment_records", failedRecords));
        return out;
    }
}
