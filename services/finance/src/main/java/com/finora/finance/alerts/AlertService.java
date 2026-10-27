package com.finora.finance.alerts;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.assistant.UnusualExpenses;
import com.finora.finance.budgets.BudgetService;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.insights.ReportModel.CurrencyReport;
import com.finora.finance.insights.ReportModel.Row;
import com.finora.finance.insights.ReportService;
import com.finora.finance.money.Currencies;
import com.finora.finance.notifications.NotificationService;
import com.finora.finance.subscriptions.Entitlements;
import com.finora.finance.summaries.SummaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explainable financial alerts. Every trigger is a deterministic backend rule over the workspace's own records (documented in docs/notifications-alerts-billing.md);
 * no language model decides, words or invents a trigger, amount, threshold or evidence. An evaluation runs only when someone asks for it (there is no background
 * scheduler, so nothing here claims continuous monitoring). Each alert has a dedupe key that encodes its event or window, so evaluating twice never repeats an alert.
 * Alerts only inform: they never change a transaction, submit anything or move money.
 */
@Service
public class AlertService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Spec(String type, boolean personal, boolean business, boolean defaultEnabled, BigDecimal defaultThreshold, Integer defaultDays, String thresholdMeaning, String trigger, String severity) {
    }

    public static final List<Spec> SPECS = List.of(
            new Spec("BUDGET_THRESHOLD", true, false, true, new BigDecimal("80"), null, "percent of the budget used", "A budget's spending this month reaches the warning percent (WARNING) or 100% (CRITICAL)", "WARNING / CRITICAL"),
            new Spec("SPENDING_CHANGE", true, true, true, new BigDecimal("30"), null, "percent increase", "Last complete month's spending is at least the threshold percent above the month before, both months having activity", "WARNING"),
            new Spec("UNUSUAL_EXPENSE", true, true, true, null, 7, null, "A recent expense is flagged by the documented large-amount or duplicate rule (assistant anomaly rules) within the look-back days", "WARNING"),
            new Spec("LOW_CASH", true, false, false, null, null, "balance below which to alert (needs a currency)", "The total of asset-account balances in the configured currency is below the configured threshold; never active until configured", "CRITICAL"),
            new Spec("RECEIVABLES_OVERDUE", false, true, true, null, null, null, "Issued invoices are past their due date with an unpaid balance in a currency", "WARNING"),
            new Spec("RECEIVABLES_DUE_SOON", false, true, true, null, 7, null, "An issued invoice with an unpaid balance is due within the configured days", "INFO"),
            new Spec("IMPORT_ISSUE", true, true, true, null, 14, null, "An import finished FAILED or PARTIAL within the look-back days", "WARNING"),
            new Spec("RECONCILIATION_UNRESOLVED", true, true, true, null, 14, null, "Statement lines are still unmatched after the configured days", "WARNING"),
            new Spec("INTEGRATION_FAILURE", false, true, true, null, 7, null, "The latest accounting synchronisation failed (or partly failed) within the look-back days with no later success", "CRITICAL / WARNING"));

    public record Setting(String alertType, boolean enabled, String threshold, Integer days, String currency, boolean isDefault, String thresholdMeaning, String trigger, String severity) {
    }

    public record SettingRequest(Boolean enabled, String threshold, Integer days, String currency) {
    }

    public record Alert(UUID id, String alertType, String severity, String title, Map<String, Object> details, LocalDate windowStart, LocalDate windowEnd, String status, OffsetDateTime generatedAt,
            OffsetDateTime acknowledgedAt, UUID acknowledgedBy, OffsetDateTime resolvedAt, String resolvedReason) {
    }

    private record Row2(UUID id, String alertType, String severity, String title, String details, LocalDate windowStart, LocalDate windowEnd, String status, OffsetDateTime generatedAt,
            OffsetDateTime acknowledgedAt, UUID acknowledgedBy, OffsetDateTime resolvedAt, String resolvedReason) {
    }

    private record Cand(String type, String severity, String key, String title, Map<String, Object> details, LocalDate from, LocalDate to) {
    }

    private record SettingRow(String alertType, boolean enabled, BigDecimal threshold, Integer days, String currency) {
    }

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final BudgetService budgets;
    private final SummaryService summaries;
    private final UnusualExpenses unusual;
    private final NotificationService notifications;
    private final Entitlements entitlements;
    private final FinanceAudit audit;

    public AlertService(JdbcClient jdbc, ReportService reports, BudgetService budgets, SummaryService summaries, UnusualExpenses unusual, NotificationService notifications,
            Entitlements entitlements, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.budgets = budgets;
        this.summaries = summaries;
        this.unusual = unusual;
        this.notifications = notifications;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    private static OffsetDateTime now(InsightsContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    private static boolean applies(Spec s, InsightsContext ctx) {
        return ctx.business() ? s.business() : s.personal();
    }

    private static boolean can(Runnable check) {
        try {
            check.run();
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- settings

    public List<Setting> settings(InsightsContext ctx) {
        ctx.requireRegionalRead();
        Map<String, SettingRow> rows = new LinkedHashMap<>();
        jdbc.sql("select alert_type, enabled, threshold, days, currency from alert_settings where tenant_id = :t").param("t", ctx.tenantId()).query(SettingRow.class).list().forEach(r -> rows.put(r.alertType(), r));
        List<Setting> out = new ArrayList<>();
        for (Spec s : SPECS) {
            if (!applies(s, ctx)) {
                continue;
            }
            SettingRow r = rows.get(s.type());
            out.add(r == null ? new Setting(s.type(), s.defaultEnabled(), s.defaultThreshold() == null ? null : s.defaultThreshold().toPlainString(), s.defaultDays(), null, true, s.thresholdMeaning(), s.trigger(), s.severity())
                    : new Setting(s.type(), r.enabled(), r.threshold() == null ? null : r.threshold().stripTrailingZeros().toPlainString(), r.days(), r.currency() == null ? null : r.currency().trim(), false, s.thresholdMeaning(), s.trigger(), s.severity()));
        }
        return out;
    }

    private Setting effective(InsightsContext ctx, String type) {
        return settings(ctx).stream().filter(s -> s.alertType().equals(type)).findFirst().orElse(null);
    }

    @Transactional
    public Setting putSetting(InsightsContext ctx, String typeRaw, SettingRequest req) {
        ctx.requireSettingsWrite();
        String type = typeRaw == null ? "" : typeRaw.trim().toUpperCase(Locale.ROOT);
        Spec spec = SPECS.stream().filter(s -> s.type().equals(type) && applies(s, ctx)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        Setting cur = effective(ctx, type);
        boolean enabled = req.enabled() == null ? cur.enabled() : req.enabled();
        BigDecimal threshold = cur.threshold() == null ? null : new BigDecimal(cur.threshold());
        if (req.threshold() != null) {
            if (spec.thresholdMeaning() == null) {
                throw Validate.invalid("This alert has no threshold");
            }
            try {
                threshold = new BigDecimal(req.threshold().trim());
            } catch (NumberFormatException e) {
                throw Validate.invalid("threshold must be a number");
            }
            boolean percent = type.equals("BUDGET_THRESHOLD") || type.equals("SPENDING_CHANGE");
            if (threshold.signum() <= 0 || threshold.scale() > 4 || (type.equals("BUDGET_THRESHOLD") && threshold.compareTo(BigDecimal.valueOf(100)) > 0) || (percent && threshold.compareTo(BigDecimal.valueOf(1000)) > 0)
                    || threshold.compareTo(Currencies.MAX_AMOUNT) > 0) {
                throw Validate.invalid(type.equals("BUDGET_THRESHOLD") ? "threshold must be a percent above 0 and at most 100" : percent ? "threshold must be a percent above 0 and at most 1000" : "threshold must be above 0");
            }
        }
        Integer days = cur.days();
        if (req.days() != null) {
            if (spec.defaultDays() == null) {
                throw Validate.invalid("This alert has no day setting");
            }
            if (req.days() < 1 || req.days() > 365) {
                throw Validate.invalid("days must be between 1 and 365");
            }
            days = req.days();
        }
        String currency = cur.currency();
        if (req.currency() != null) {
            if (!type.equals("LOW_CASH")) {
                throw Validate.invalid("This alert has no currency setting");
            }
            currency = req.currency().trim().toUpperCase(Locale.ROOT);
            if (!Currencies.supported(currency)) {
                throw Validate.invalid("currency is not supported");
            }
        }
        if (type.equals("LOW_CASH") && enabled && (threshold == null || currency == null)) {
            throw Validate.invalid("Low-cash alerts need an explicit currency and threshold; none is assumed");
        }
        jdbc.sql("""
                insert into alert_settings (tenant_id, alert_type, enabled, threshold, days, currency, updated_by, updated_at) values (:t, :ty, :e, :th, :d, :c, :u, :now)
                on conflict (tenant_id, alert_type) do update set enabled = :e, threshold = :th, days = :d, currency = :c, updated_by = :u, updated_at = :now
                """).param("t", ctx.tenantId()).param("ty", type).param("e", enabled).param("th", threshold).param("d", days).param("c", currency).param("u", ctx.userId()).param("now", now(ctx)).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "ALERT_SETTING_UPDATED", "ALERT_SETTING", type, "enabled=" + enabled);
        return effective(ctx, type);
    }

    // ---------------------------------------------------------------- listing and lifecycle

    private Alert view(Row2 r) {
        Map<String, Object> d = JSON.readValue(r.details(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
        return new Alert(r.id(), r.alertType(), r.severity(), r.title(), d, r.windowStart(), r.windowEnd(), r.status(), r.generatedAt(), r.acknowledgedAt(), r.acknowledgedBy(), r.resolvedAt(), r.resolvedReason());
    }

    private static final String COLS = "id, alert_type, severity, title, details, window_start, window_end, status, generated_at, acknowledged_at, acknowledged_by, resolved_at, resolved_reason";

    public Map<String, Object> list(InsightsContext ctx, String status, String type, int limit, int offset) {
        ctx.requireRegionalRead();
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null && !List.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(st)) {
            throw Validate.invalid("status must be OPEN, ACKNOWLEDGED or RESOLVED");
        }
        String ty = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        if (ty != null && SPECS.stream().noneMatch(s -> s.type().equals(ty))) {
            throw Validate.invalid("unknown alert type");
        }
        int lim = Math.max(1, Math.min(100, limit));
        int off = Math.max(0, offset);
        String where = "tenant_id = :t and (cast(:st as text) is null or status = :st) and (cast(:ty as text) is null or alert_type = :ty)";
        long total = jdbc.sql("select count(*) from alerts where " + where).param("t", ctx.tenantId()).param("st", st).param("ty", ty).query(Long.class).single();
        List<Alert> items = jdbc.sql("select " + COLS + " from alerts where " + where + " order by generated_at desc, id limit :l offset :o").param("t", ctx.tenantId()).param("st", st).param("ty", ty)
                .param("l", lim).param("o", off).query(Row2.class).list().stream().map(this::view).toList();
        long open = jdbc.sql("select count(*) from alerts where tenant_id = :t and status = 'OPEN'").param("t", ctx.tenantId()).query(Long.class).single();
        return Map.of("items", items, "total", total, "open", open, "limit", lim, "offset", off);
    }

    public Alert get(InsightsContext ctx, UUID id) {
        ctx.requireRegionalRead();
        return jdbc.sql("select " + COLS + " from alerts where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id).query(Row2.class).optional().map(this::view)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    @Transactional
    public Alert acknowledge(InsightsContext ctx, UUID id) {
        ctx.requireRegionalRead();
        Alert a = get(ctx, id);
        if (!"OPEN".equals(a.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only an open alert can be acknowledged (it is " + a.status().toLowerCase(Locale.ROOT) + ")");
        }
        jdbc.sql("update alerts set status = 'ACKNOWLEDGED', acknowledged_at = :now, acknowledged_by = :u where tenant_id = :t and id = :id and status = 'OPEN'").param("now", now(ctx)).param("u", ctx.userId())
                .param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "ALERT_ACKNOWLEDGED", "ALERT", id, a.alertType());
        return get(ctx, id);
    }

    @Transactional
    public Alert resolve(InsightsContext ctx, UUID id, String reason) {
        ctx.requireRegionalRead();
        String why = Validate.text("reason", reason, 1, 200, false);
        Alert a = get(ctx, id);
        if ("RESOLVED".equals(a.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "That alert is already resolved");
        }
        jdbc.sql("update alerts set status = 'RESOLVED', resolved_at = :now, resolved_by = :u, resolved_reason = :r where tenant_id = :t and id = :id and status <> 'RESOLVED'").param("now", now(ctx))
                .param("u", ctx.userId()).param("r", why == null ? "resolved by a person" : why).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "ALERT_RESOLVED", "ALERT", id, a.alertType());
        return get(ctx, id);
    }

    // ---------------------------------------------------------------- evaluation

    /** Runs every enabled, applicable rule once. Safe to repeat: an alert is created at most once per dedupe key. Conditions that no longer hold resolve their open alerts. */
    @Transactional
    public Map<String, Object> evaluate(InsightsContext ctx) {
        ctx.requireRegionalRead();
        entitlements.require(ctx, "alerts");
        LocalDate today = ctx.today();
        Map<String, Integer> created = new LinkedHashMap<>();
        Map<String, Integer> resolved = new LinkedHashMap<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        for (Setting s : settings(ctx)) {
            if (!s.enabled()) {
                skipped.add(Map.of("type", s.alertType(), "reason", "disabled"));
                continue;
            }
            List<Cand> found;
            try {
                found = candidates(ctx, s, today);
            } catch (ApiException e) {
                skipped.add(Map.of("type", s.alertType(), "reason", "your role cannot read the data this rule needs"));
                continue;
            }
            if (found == null) {
                skipped.add(Map.of("type", s.alertType(), "reason", "not enough information to evaluate (for example no accounts in the configured currency)"));
                continue;
            }
            int made = 0;
            Set<String> current = new LinkedHashSet<>();
            for (Cand c : found) {
                current.add(c.key());
                if (insert(ctx, c)) {
                    made++;
                }
            }
            created.put(s.alertType(), made);
            if (conditionType(s.alertType())) {
                int r = resolveCleared(ctx, s.alertType(), current, today);
                if (r > 0) {
                    resolved.put(s.alertType(), r);
                }
            }
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "ALERTS_EVALUATED", "ALERT", null, "created " + created.values().stream().mapToInt(Integer::intValue).sum());
        return Map.of("evaluated_on", today.toString(), "created", created, "resolved", resolved, "skipped", skipped,
                "note", "Based on the records entered up to now. Records that have not been entered, and bank balances that were not recorded, are not reflected; nothing is monitored between evaluations.");
    }

    private static boolean conditionType(String type) {
        return List.of("BUDGET_THRESHOLD", "LOW_CASH", "RECEIVABLES_OVERDUE", "RECEIVABLES_DUE_SOON", "RECONCILIATION_UNRESOLVED", "INTEGRATION_FAILURE").contains(type);
    }

    private boolean insert(InsightsContext ctx, Cand c) {
        UUID id = UUID.randomUUID();
        int n = jdbc.sql("""
                insert into alerts (id, tenant_id, alert_type, severity, dedupe_key, title, details, window_start, window_end, generated_at) values (:id, :t, :ty, :sev, :k, :title, :d, :f, :to, :now)
                on conflict (tenant_id, dedupe_key) do nothing
                """).param("id", id).param("t", ctx.tenantId()).param("ty", c.type()).param("sev", c.severity()).param("k", c.key()).param("title", c.title().length() > 200 ? c.title().substring(0, 200) : c.title())
                .param("d", JSON.writeValueAsString(c.details())).param("f", c.from()).param("to", c.to()).param("now", now(ctx)).update();
        if (n == 0) {
            return false;
        }
        boolean integration = c.type().equals("INTEGRATION_FAILURE");
        notifications.create(ctx.tenantId(), ctx.clock(), null, integration ? "SETTINGS" : "REPORTS", integration ? "INTEGRATION" : "ALERT", c.severity(), c.title(),
                "Open the alerts list for the trigger and figures.", Map.of("alert_id", id.toString(), "alert_type", c.type()), "alert:" + id);
        return true;
    }

    private int resolveCleared(InsightsContext ctx, String type, Set<String> current, LocalDate today) {
        List<Row2> open = jdbc.sql("select " + COLS + " from alerts where tenant_id = :t and alert_type = :ty and status <> 'RESOLVED'").param("t", ctx.tenantId()).param("ty", type).query(Row2.class).list();
        int n = 0;
        for (Row2 r : open) {
            String key = jdbc.sql("select dedupe_key from alerts where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", r.id()).query(String.class).single();
            if (!current.contains(key)) {
                n += jdbc.sql("update alerts set status = 'RESOLVED', resolved_at = :now, resolved_reason = 'The condition no longer holds' where tenant_id = :t and id = :id and status <> 'RESOLVED'")
                        .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", r.id()).update();
            }
        }
        return n;
    }

    private List<Cand> candidates(InsightsContext ctx, Setting s, LocalDate today) {
        LocalDate week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Cand> out = new ArrayList<>();
        switch (s.alertType()) {
            case "BUDGET_THRESHOLD" -> {
                BigDecimal warn = new BigDecimal(s.threshold());
                var summary = budgets.summary(ctx.personal(), null);
                for (var v : summary.items()) {
                    if (v.percentUsed() == null) {
                        continue;
                    }
                    BigDecimal pct = new BigDecimal(v.percentUsed());
                    String level = pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? "OVER" : pct.compareTo(warn) >= 0 ? "WARNING" : null;
                    if (level != null) {
                        String name = v.categoryName() == null ? "All spending" : v.categoryName();
                        Map<String, Object> d = details("budget_id", v.id().toString(), "month", summary.month(), "currency", v.currency().trim(), "budget", v.amount(), "spent", v.spent(), "percent_used", v.percentUsed(), "warn_percent", warn.toPlainString());
                        out.add(new Cand(s.alertType(), "OVER".equals(level) ? "CRITICAL" : "WARNING", "budget:" + v.id() + ":" + summary.month() + ":" + level,
                                "Budget \"" + trim(name) + "\" " + ("OVER".equals(level) ? "is over its limit" : "has used " + v.percentUsed() + "%"), d, summary.from(), summary.to()));
                    }
                }
            }
            case "SPENDING_CHANGE" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                YearMonth prior = last.minusMonths(1);
                String column = ctx.business() ? "expenses" : "net_expense";
                var rep = reports.report(ctx, ctx.business() ? "business-performance" : "cash-flow", new ReportService.Params(prior.atDay(1).toString(), last.atEndOfMonth().toString(), "month", null, null, null, null, null));
                BigDecimal limit = new BigDecimal(s.threshold());
                for (CurrencyReport c : rep.currencies()) {
                    Row a = c.rows().get(0);
                    Row b = c.rows().get(c.rows().size() - 1);
                    if (c.rows().size() < 2 || !a.activity() || !b.activity()) {
                        continue;
                    }
                    BigDecimal before = new BigDecimal(a.values().get(column));
                    BigDecimal now = new BigDecimal(b.values().get(column));
                    if (before.signum() <= 0 || now.compareTo(before) <= 0) {
                        continue;
                    }
                    BigDecimal pct = now.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 2, RoundingMode.HALF_EVEN);
                    if (pct.compareTo(limit) >= 0) {
                        out.add(new Cand(s.alertType(), "WARNING", "spending:" + c.currency() + ":" + last, "Spending rose " + pct.toPlainString() + "% in " + c.currency() + " in " + last,
                                details("currency", c.currency(), "month", last.toString(), "previous_month", prior.toString(), "previous_amount", a.values().get(column), "amount", b.values().get(column), "change_percent", pct.toPlainString(),
                                        "threshold_percent", limit.toPlainString(), "measure", column), prior.atDay(1), last.atEndOfMonth()));
                    }
                }
            }
            case "UNUSUAL_EXPENSE" -> {
                ctx.requireFullReports();
                LocalDate from = today.minusDays(s.days() - 1L);
                for (var f : unusual.detect(ctx, from, today)) {
                    out.add(new Cand(s.alertType(), "WARNING", "unusual:" + f.rule() + ":" + f.recordId(), ("LARGE_AMOUNT".equals(f.rule()) ? "Unusually large expense of " : "Possible duplicate expense of ") + f.amount() + " " + f.currency(),
                            details("rule", f.rule(), "record_id", f.recordId().toString(), "date", f.date().toString(), "currency", f.currency(), "amount", f.amount(), "lookback_days", s.days()), from, today));
                }
            }
            case "LOW_CASH" -> {
                var nw = summaries.netWorth(ctx.personal(), today);
                BigDecimal limit = new BigDecimal(s.threshold());
                var entry = nw.currencies().stream().filter(c -> c.currency().trim().equals(s.currency())).findFirst().orElse(null);
                if (entry == null) {
                    return null;
                }
                BigDecimal balance = new BigDecimal(entry.assets());
                if (balance.compareTo(limit) < 0) {
                    out.add(new Cand(s.alertType(), "CRITICAL", "lowcash:" + s.currency() + ":" + week, "Cash balance in " + s.currency() + " is below your threshold",
                            details("currency", s.currency(), "balance", entry.assets(), "threshold", Currencies.format(limit.setScale(Currencies.exponentOrThrow(s.currency()), RoundingMode.HALF_EVEN), s.currency()), "as_of", today.toString(),
                                    "basis", "recorded asset-account balances"), week, week.plusDays(6)));
                }
            }
            case "RECEIVABLES_OVERDUE" -> {
                ctx.requireReports();
                var rep = reports.report(ctx, "receivables", new ReportService.Params(null, null, null, null, null, null, null, null));
                for (CurrencyReport c : rep.currencies()) {
                    BigDecimal overdue = new BigDecimal(c.totals().get("overdue"));
                    if (overdue.signum() > 0) {
                        out.add(new Cand(s.alertType(), "WARNING", "overdue:" + c.currency() + ":" + week, "Overdue invoices in " + c.currency() + ": " + c.totals().get("overdue"),
                                details("currency", c.currency(), "overdue", c.totals().get("overdue"), "outstanding", c.totals().get("outstanding"), "invoices", c.totals().get("invoices"), "as_of", today.toString()), week, week.plusDays(6)));
                    }
                }
            }
            case "RECEIVABLES_DUE_SOON" -> {
                ctx.requireReports();
                record Due(UUID id, String number, LocalDate dueDate, String currency, BigDecimal outstanding) {
                }
                List<Due> dues = jdbc.sql("""
                        select i.id, i.number, i.due_date, i.currency, i.total - coalesce(pd.paid, 0) as outstanding from invoices i
                        left join lateral (select sum(a.amount) as paid from payment_allocations a join payments p on p.tenant_id = a.tenant_id and p.id = a.payment_id
                                           where a.tenant_id = i.tenant_id and a.invoice_id = i.id and p.status = 'RECORDED') pd on true
                        where i.tenant_id = :t and i.status = 'ISSUED' and i.due_date between :today and :until and i.total - coalesce(pd.paid, 0) > 0 order by i.due_date, i.number limit 200
                        """).param("t", ctx.tenantId()).param("today", today).param("until", today.plusDays(s.days())).query(Due.class).list();
                for (Due d : dues) {
                    String cur = d.currency().trim();
                    out.add(new Cand(s.alertType(), "INFO", "duesoon:" + d.id(), "Invoice " + d.number() + " is due on " + d.dueDate(),
                            details("invoice_id", d.id().toString(), "number", d.number(), "due_date", d.dueDate().toString(), "currency", cur, "outstanding", Currencies.format(d.outstanding(), cur), "within_days", s.days()), today, d.dueDate()));
                }
            }
            case "IMPORT_ISSUE" -> {
                ctx.requireImports(Action.READ);
                record Imp(UUID id, String status, int committedRows, int rejectedRows, LocalDate done) {
                }
                for (Imp i : jdbc.sql("""
                        select id, status, committed_rows, rejected_rows, completed_at::date as done from finance_imports where tenant_id = :t and status in ('FAILED', 'PARTIAL') and completed_at::date >= :since
                        order by completed_at desc limit 100
                        """).param("t", ctx.tenantId()).param("since", today.minusDays(s.days())).query(Imp.class).list()) {
                    out.add(new Cand(s.alertType(), "WARNING", "import:" + i.id(), "An import finished " + i.status().toLowerCase(Locale.ROOT) + " (" + i.rejectedRows() + " row(s) rejected)",
                            details("import_id", i.id().toString(), "status", i.status(), "committed_rows", i.committedRows(), "rejected_rows", i.rejectedRows(), "finished_on", i.done().toString()), i.done(), i.done()));
                }
            }
            case "RECONCILIATION_UNRESOLVED" -> {
                ctx.requireImports(Action.READ);
                long unmatched = jdbc.sql("select count(*) from finance_statement_lines where tenant_id = :t and status = 'UNMATCHED' and line_date <= :cut").param("t", ctx.tenantId()).param("cut", today.minusDays(s.days()))
                        .query(Long.class).single();
                if (unmatched > 0) {
                    out.add(new Cand(s.alertType(), "WARNING", "recon:" + week, unmatched + " statement line(s) are still unmatched after " + s.days() + " days",
                            details("unmatched_lines", unmatched, "older_than_days", s.days(), "as_of", today.toString()), week, week.plusDays(6)));
                }
            }
            case "INTEGRATION_FAILURE" -> {
                ctx.requireSettingsRead();
                record Run(UUID id, String provider, String status, LocalDate done) {
                }
                for (Run r : jdbc.sql("""
                        select r.id, r.provider, r.status, r.finished_at::date as done from accounting_sync_runs r
                        where r.tenant_id = :t and r.status in ('FAILED', 'PARTIAL') and r.finished_at::date >= :since
                          and not exists (select 1 from accounting_sync_runs s where s.tenant_id = r.tenant_id and s.provider = r.provider and s.status = 'SUCCEEDED' and s.finished_at > r.finished_at)
                        order by r.finished_at desc limit 20
                        """).param("t", ctx.tenantId()).param("since", today.minusDays(s.days())).query(Run.class).list()) {
                    out.add(new Cand(s.alertType(), "FAILED".equals(r.status()) ? "CRITICAL" : "WARNING", "integration:" + r.id(), "An accounting synchronisation " + r.status().toLowerCase(Locale.ROOT),
                            details("run_id", r.id().toString(), "provider", r.provider(), "status", r.status(), "finished_on", r.done().toString()), r.done(), r.done()));
                }
            }
            default -> throw new IllegalStateException("unknown alert type");
        }
        return out;
    }

    private static String trim(String s) {
        return s.length() > 60 ? s.substring(0, 59) + "…" : s;
    }

    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
