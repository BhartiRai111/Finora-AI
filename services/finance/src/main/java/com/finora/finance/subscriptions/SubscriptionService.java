package com.finora.finance.subscriptions;

import com.finora.commons.authz.Role;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.notifications.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans and the subscription lifecycle of one workspace: ACTIVE, PAST_DUE, CANCELLED, EXPIRED. The workspace Owner may switch between plans that need no
 * payment, cancel and reactivate; PAST_DUE and EXPIRED and any plan that requires payment can only come from a VERIFIED billing event (see
 * {@link BillingEventProcessor}). There is no trial and no grace period (no policy is approved). A workspace with no row is ACTIVE on the default
 * provisional plan. Nothing here touches financial records. Platform-staff plan management does not exist: no staff identity model is approved.
 */
@Service
public class SubscriptionService {

    public record Entitlement(String key, boolean enabled, Long limit) {
    }

    public record Plan(String planKey, String name, String description, boolean provisional, boolean requiresPayment, String status, List<Entitlement> entitlements) {
    }

    public record Subscription(String status, Plan plan, OffsetDateTime startedAt, LocalDate currentPeriodEnd, boolean implicitDefault, String cancelReason,
            List<Entitlements.Effective> effective, List<String> notices) {
    }

    public record Event(UUID id, String fromStatus, String toStatus, String fromPlan, String toPlan, String source, UUID actorId, String reason, OffsetDateTime createdAt) {
    }

    private record PlanRow(String planKey, String name, String description, boolean provisional, boolean requiresPayment, String status) {
    }

    private record SubRow(String planKey, String status, OffsetDateTime startedAt, LocalDate currentPeriodEnd, String cancelReason) {
    }

    private static final Map<String, List<String>> BILLING_TRANSITIONS = Map.of("ACTIVE", List.of("PAST_DUE", "CANCELLED", "EXPIRED"), "PAST_DUE", List.of("ACTIVE", "CANCELLED", "EXPIRED"),
            "CANCELLED", List.of("ACTIVE", "EXPIRED"), "EXPIRED", List.of("ACTIVE"));

    private final JdbcClient jdbc;
    private final Entitlements entitlements;
    private final FinanceAudit audit;
    private final NotificationService notifications;

    public SubscriptionService(JdbcClient jdbc, Entitlements entitlements, FinanceAudit audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.audit = audit;
        this.notifications = notifications;
    }

    private static OffsetDateTime now(Clock c) {
        return OffsetDateTime.ofInstant(c.instant(), ZoneOffset.UTC);
    }

    private static void requireOwner(InsightsContext ctx) {
        if (ctx.role() != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    // ---------------------------------------------------------------- reading

    public List<Plan> plans(InsightsContext ctx) {
        ctx.requireRegionalRead();
        return jdbc.sql("select plan_key, name, description, provisional, requires_payment, status from subscription_plans where status = 'AVAILABLE' order by plan_key").query(PlanRow.class).list()
                .stream().map(this::plan).toList();
    }

    private Plan plan(PlanRow p) {
        List<Entitlement> ents = jdbc.sql("select entitlement_key, enabled, limit_value from plan_entitlements where plan_key = :p order by entitlement_key").param("p", p.planKey())
                .query((rs, i) -> {
                    long limit = rs.getLong(3);
                    return new Entitlement(rs.getString(1), rs.getBoolean(2), rs.wasNull() ? null : limit);
                }).list();
        return new Plan(p.planKey(), p.name(), p.description(), p.provisional(), p.requiresPayment(), p.status(), ents);
    }

    private PlanRow planRow(String key) {
        return jdbc.sql("select plan_key, name, description, provisional, requires_payment, status from subscription_plans where plan_key = :k").param("k", key).query(PlanRow.class).optional()
                .orElseThrow(() -> Validate.invalid("That plan does not exist"));
    }

    public Subscription current(InsightsContext ctx) {
        ctx.requireRegionalRead();
        return view(ctx.tenantId());
    }

    private Subscription view(UUID tenant) {
        SubRow row = jdbc.sql("select plan_key, status, started_at, current_period_end, cancel_reason from workspace_subscriptions where tenant_id = :t").param("t", tenant).query(SubRow.class).optional().orElse(null);
        String planKey = row == null ? Entitlements.DEFAULT_PLAN : row.planKey();
        String status = row == null ? "ACTIVE" : row.status();
        List<Entitlements.Effective> eff = Entitlements.KEYS.stream().map(k -> entitlements.effective(tenant, k)).toList();
        List<String> notices = new ArrayList<>();
        PlanRow plan = planRow(planKey);
        if (plan.provisional()) {
            notices.add("This plan is provisional: its contents are engineering defaults, not an approved commercial offer, and no price applies.");
        }
        switch (status) {
            case "PAST_DUE" -> notices.add("Payment for this subscription is overdue. Premium features are unavailable until a verified payment is received. Your financial data is not affected.");
            case "CANCELLED" -> notices.add("This subscription is cancelled. Premium features are unavailable; your financial data is kept and remains available.");
            case "EXPIRED" -> notices.add("This subscription has expired. Premium features are unavailable; your financial data is kept and remains available.");
            default -> {
            }
        }
        return new Subscription(status, plan(plan), row == null ? null : row.startedAt(), row == null ? null : row.currentPeriodEnd(), row == null, row == null ? null : row.cancelReason(), eff, notices);
    }

    public Map<String, Object> history(InsightsContext ctx, int limit, int offset) {
        ctx.requireSettingsRead();
        int lim = Math.max(1, Math.min(100, limit));
        long total = jdbc.sql("select count(*) from subscription_events where tenant_id = :t").param("t", ctx.tenantId()).query(Long.class).single();
        List<Event> items = jdbc.sql("""
                select id, from_status, to_status, from_plan, to_plan, source, actor_id, reason, created_at from subscription_events where tenant_id = :t
                order by created_at desc, id limit :l offset :o
                """).param("t", ctx.tenantId()).param("l", lim).param("o", Math.max(0, offset)).query(Event.class).list();
        return Map.of("items", items, "total", total, "limit", lim, "offset", Math.max(0, offset));
    }

    // ---------------------------------------------------------------- lifecycle (owner)

    /** Makes sure a row exists (default plan, ACTIVE) and locks it for the transition. */
    private SubRow lock(UUID tenant, Clock clock) {
        jdbc.sql("insert into workspace_subscriptions (tenant_id, plan_key, status, started_at, updated_at) values (:t, :p, 'ACTIVE', :now, :now) on conflict do nothing")
                .param("t", tenant).param("p", Entitlements.DEFAULT_PLAN).param("now", now(clock)).update();
        return jdbc.sql("select plan_key, status, started_at, current_period_end, cancel_reason from workspace_subscriptions where tenant_id = :t for update").param("t", tenant).query(SubRow.class).single();
    }

    private void record(UUID tenant, Clock clock, SubRow before, String toStatus, String toPlan, String source, UUID actor, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into subscription_events (id, tenant_id, from_status, to_status, from_plan, to_plan, source, actor_id, reason, created_at) values (:id, :t, :fs, :ts, :fp, :tp, :src, :a, :r, :now)
                """).param("id", id).param("t", tenant).param("fs", before.status()).param("ts", toStatus).param("fp", before.planKey()).param("tp", toPlan).param("src", source).param("a", actor)
                .param("r", reason).param("now", now(clock)).update();
        notifications.create(tenant, clock, null, "SETTINGS", "SUBSCRIPTION", "ACTIVE".equals(toStatus) ? "INFO" : "WARNING", "Subscription " + toStatus.toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                "Plan " + toPlan + (before.planKey().equals(toPlan) ? "" : " (was " + before.planKey() + ")") + ".", Map.of("status", toStatus, "plan", toPlan), "subscription:" + id);
    }

    @Transactional
    public Subscription changePlan(InsightsContext ctx, String planKey) {
        requireOwner(ctx);
        if (planKey == null || planKey.isBlank()) {
            throw Validate.invalid("plan_key is required");
        }
        PlanRow target = planRow(planKey.trim());
        if (!"AVAILABLE".equals(target.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "That plan is no longer available");
        }
        SubRow s = lock(ctx.tenantId(), ctx.clock());
        if (!"ACTIVE".equals(s.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The plan can only be changed while the subscription is active (it is " + s.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")");
        }
        if (target.requiresPayment()) {
            throw new ApiException(ErrorCode.CONFLICT, "That plan requires a verified payment, and no billing provider is configured. A plan that needs payment cannot be activated from here.");
        }
        if (s.planKey().equals(target.planKey())) {
            return view(ctx.tenantId());
        }
        jdbc.sql("update workspace_subscriptions set plan_key = :p, version = version + 1, updated_at = :now where tenant_id = :t").param("p", target.planKey()).param("now", now(ctx.clock())).param("t", ctx.tenantId()).update();
        record(ctx.tenantId(), ctx.clock(), s, "ACTIVE", target.planKey(), "OWNER", ctx.userId(), null);
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "SUBSCRIPTION_PLAN_CHANGED", "SUBSCRIPTION", ctx.tenantId(), "from " + s.planKey() + " to " + target.planKey());
        return view(ctx.tenantId());
    }

    @Transactional
    public Subscription cancel(InsightsContext ctx, String reason) {
        requireOwner(ctx);
        String why = Validate.text("reason", reason, 1, 200, false);
        SubRow s = lock(ctx.tenantId(), ctx.clock());
        if (!"ACTIVE".equals(s.status()) && !"PAST_DUE".equals(s.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only an active or past-due subscription can be cancelled (it is " + s.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")");
        }
        jdbc.sql("update workspace_subscriptions set status = 'CANCELLED', cancel_reason = :r, version = version + 1, updated_at = :now where tenant_id = :t").param("r", why).param("now", now(ctx.clock()))
                .param("t", ctx.tenantId()).update();
        record(ctx.tenantId(), ctx.clock(), s, "CANCELLED", s.planKey(), "OWNER", ctx.userId(), why);
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "SUBSCRIPTION_CANCELLED", "SUBSCRIPTION", ctx.tenantId(), "plan " + s.planKey());
        return view(ctx.tenantId());
    }

    @Transactional
    public Subscription reactivate(InsightsContext ctx) {
        requireOwner(ctx);
        SubRow s = lock(ctx.tenantId(), ctx.clock());
        if (!"CANCELLED".equals(s.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only a cancelled subscription can be reactivated (it is " + s.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")");
        }
        if (planRow(s.planKey()).requiresPayment()) {
            throw new ApiException(ErrorCode.CONFLICT, "This plan requires a verified payment, and no billing provider is configured, so it cannot be reactivated from here.");
        }
        jdbc.sql("update workspace_subscriptions set status = 'ACTIVE', cancel_reason = null, version = version + 1, updated_at = :now where tenant_id = :t").param("now", now(ctx.clock())).param("t", ctx.tenantId()).update();
        record(ctx.tenantId(), ctx.clock(), s, "ACTIVE", s.planKey(), "OWNER", ctx.userId(), null);
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "SUBSCRIPTION_REACTIVATED", "SUBSCRIPTION", ctx.tenantId(), "plan " + s.planKey());
        return view(ctx.tenantId());
    }

    // ---------------------------------------------------------------- verified billing events only

    /**
     * Applies a status (and optionally a plan and period end) decided by a VERIFIED billing event. Invalid transitions are refused. Never reachable from a client request.
     * Returns false when nothing changed: the subscription is already in the requested state and plan, or the lifecycle does not allow the move.
     */
    @Transactional
    boolean applyVerifiedBilling(UUID tenant, Clock clock, String toStatus, String planKey, LocalDate periodEnd, String reason) {
        SubRow s = lock(tenant, clock);
        String toPlan = planKey == null ? s.planKey() : planRow(planKey).planKey();
        if (s.status().equals(toStatus) && s.planKey().equals(toPlan)) {
            return false;
        }
        if (!s.status().equals(toStatus) && !BILLING_TRANSITIONS.getOrDefault(s.status(), List.of()).contains(toStatus)) {
            return false; // an event that does not fit the lifecycle changes nothing (and is not an error for the event's other effects)
        }
        jdbc.sql("update workspace_subscriptions set status = :s, plan_key = :p, current_period_end = coalesce(:pe, current_period_end), version = version + 1, updated_at = :now where tenant_id = :t")
                .param("s", toStatus).param("p", toPlan).param("pe", periodEnd).param("now", now(clock)).param("t", tenant).update();
        record(tenant, clock, s, toStatus, toPlan, "BILLING_EVENT", null, reason);
        return true;
    }
}
