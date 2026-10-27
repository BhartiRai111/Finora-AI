package com.finora.finance.notifications;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-app notifications of one workspace. No external delivery channel (email, SMS, push, webhook) is approved or configured, so nothing here is ever sent
 * outside the application and no record claims delivery. A notification belongs to a workspace; {@code recipient_user_id} restricts it to one user, otherwise
 * it is visible to the members whose role matches its audience. Read state is per user. Metadata holds a few safe identifiers only (never amounts of
 * individual records, tax identifiers, tokens or names), and creating a notification is idempotent per (workspace, source key).
 */
@Service
public class NotificationService {

    public static final List<String> CATEGORIES = List.of("IMPORT", "RECONCILIATION", "ALERT", "SUBSCRIPTION", "BILLING", "INTEGRATION", "SECURITY", "OPERATIONS");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Item(UUID id, String category, String severity, String title, String body, Map<String, String> metadata, boolean read, OffsetDateTime createdAt) {
    }

    public record Page(List<Item> items, long total, long unread, int limit, int offset) {
    }

    private record Row(UUID id, String category, String severity, String title, String body, String metadata, boolean read, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- creating (called by features, never by a client)

    /**
     * Records a notification once. {@code sourceKey} identifies the event (for example "import:{id}:COMPLETED"); a repeat is ignored and returns false.
     * Returns true when a new notification was created.
     */
    public boolean create(java.util.UUID tenantId, java.time.Clock clock, UUID recipientUserId, String audience, String category, String severity, String title, String body,
            Map<String, String> metadata, String sourceKey) {
        if (!CATEGORIES.contains(category) || !List.of("INFO", "WARNING", "CRITICAL").contains(severity) || !List.of("REPORTS", "SETTINGS", "OWNER").contains(audience)) {
            throw new IllegalArgumentException("invalid notification");
        }
        String meta = metadata == null || metadata.isEmpty() ? null : JSON.writeValueAsString(metadata);
        return jdbc.sql("""
                insert into notifications (id, tenant_id, recipient_user_id, audience, category, severity, title, body, metadata, source_key, created_at)
                values (:id, :t, :u, :a, :c, :s, :title, :body, :m, :k, :now) on conflict (tenant_id, source_key) do nothing
                """).param("id", UUID.randomUUID()).param("t", tenantId).param("u", recipientUserId).param("a", audience).param("c", category).param("s", severity)
                .param("title", trim(title, 160)).param("body", body == null ? null : trim(body, 500)).param("m", meta).param("k", sourceKey)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update() == 1;
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ---------------------------------------------------------------- reading

    private static boolean allows(Runnable check) {
        try {
            check.run();
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** The audiences this caller belongs to, decided only from the verified token's role. */
    static List<String> audiences(InsightsContext ctx) {
        List<String> out = new ArrayList<>();
        if (allows(ctx::requireReports)) {
            out.add("REPORTS");
        }
        if (allows(ctx::requireSettingsRead)) {
            out.add("SETTINGS");
        }
        if (ctx.role() == com.finora.commons.authz.Role.OWNER) {
            out.add("OWNER");
        }
        if (out.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        return out;
    }

    private static final String VISIBLE = "n.tenant_id = :t and (n.recipient_user_id = :u or (n.recipient_user_id is null and n.audience = any(:aud)))";

    public Page list(InsightsContext ctx, boolean unreadOnly, String category, int limit, int offset) {
        List<String> aud = audiences(ctx);
        String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        if (cat != null && !CATEGORIES.contains(cat)) {
            throw Validate.invalid("category must be one of " + CATEGORIES);
        }
        int lim = Math.max(1, Math.min(100, limit));
        int off = Math.max(0, offset);
        String where = VISIBLE + " and (cast(:cat as text) is null or n.category = :cat)";
        String unreadClause = " and not exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :u)";
        String[] auds = aud.toArray(String[]::new);
        long total = jdbc.sql("select count(*) from notifications n where " + where + (unreadOnly ? unreadClause : "")).param("t", ctx.tenantId()).param("u", ctx.userId()).param("aud", auds)
                .param("cat", cat).query(Long.class).single();
        List<Item> items = jdbc.sql("""
                select n.id, n.category, n.severity, n.title, n.body, n.metadata, exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :u) as read, n.created_at
                from notifications n where %s%s order by n.created_at desc, n.id limit :l offset :o
                """.formatted(where, unreadOnly ? unreadClause : "")).param("t", ctx.tenantId()).param("u", ctx.userId()).param("aud", auds).param("cat", cat).param("l", lim).param("o", off)
                .query(Row.class).list().stream().map(NotificationService::item).toList();
        return new Page(items, total, unreadCount(ctx, aud), lim, off);
    }

    public long unreadCount(InsightsContext ctx) {
        return unreadCount(ctx, audiences(ctx));
    }

    private long unreadCount(InsightsContext ctx, List<String> aud) {
        return jdbc.sql("select count(*) from notifications n where " + VISIBLE + " and not exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :u)")
                .param("t", ctx.tenantId()).param("u", ctx.userId()).param("aud", aud.toArray(String[]::new)).query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private static Item item(Row r) {
        Map<String, String> meta = r.metadata() == null ? Map.of() : JSON.readValue(r.metadata(), Map.class);
        return new Item(r.id(), r.category(), r.severity(), r.title(), r.body(), meta, r.read(), r.createdAt());
    }

    // ---------------------------------------------------------------- marking read

    /** Marks one visible notification read for the caller only. Idempotent. A notification the caller cannot see is reported as not found. */
    @Transactional
    public Item markRead(InsightsContext ctx, UUID id) {
        List<String> aud = audiences(ctx);
        Row visible = jdbc.sql("""
                select n.id, n.category, n.severity, n.title, n.body, n.metadata, false as read, n.created_at from notifications n where %s and n.id = :id
                """.formatted(VISIBLE)).param("t", ctx.tenantId()).param("u", ctx.userId()).param("aud", aud.toArray(String[]::new)).param("id", id).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        jdbc.sql("insert into notification_reads (tenant_id, notification_id, user_id, read_at) values (:t, :n, :u, :now) on conflict do nothing").param("t", ctx.tenantId()).param("n", id)
                .param("u", ctx.userId()).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
        return item(new Row(visible.id(), visible.category(), visible.severity(), visible.title(), visible.body(), visible.metadata(), true, visible.createdAt()));
    }

    /** Marks everything the caller can see as read, for the caller only. Returns how many were newly marked. */
    @Transactional
    public int markAllRead(InsightsContext ctx) {
        List<String> aud = audiences(ctx);
        return jdbc.sql("""
                insert into notification_reads (tenant_id, notification_id, user_id, read_at)
                select n.tenant_id, n.id, :u, :now from notifications n where %s and not exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :u)
                on conflict do nothing
                """.formatted(VISIBLE)).param("t", ctx.tenantId()).param("u", ctx.userId()).param("aud", aud.toArray(String[]::new))
                .param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
    }

    public Map<String, Object> channels(InsightsContext ctx) {
        audiences(ctx);
        return Map.of("in_app", Map.of("status", "AVAILABLE"),
                "external", Map.of("status", "NOT_CONFIGURED", "message", "No email, SMS, push or webhook delivery provider has been approved or configured. Notifications appear in the application only; nothing is sent outside it."));
    }
}
