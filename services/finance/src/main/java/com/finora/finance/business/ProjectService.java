package com.finora.finance.business;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business projects. A project has one currency (so its revenue, costs and budget are always comparable without any
 * conversion), an optional client and an optional cost budget. Completed or cancelled projects accept no new expenses or
 * invoices. The cost budget is a ceiling for expenses posted to the project; exceeding it is reported, never blocked.
 */
@Service
public class ProjectService {

    static final Set<String> STATUSES = Set.of("PLANNED", "ACTIVE", "ON_HOLD", "COMPLETED", "CANCELLED");
    static final Set<String> OPEN = Set.of("PLANNED", "ACTIVE", "ON_HOLD");

    public record Project(UUID id, String name, String code, UUID clientId, String clientName, String status, String currency, String budgetAmount,
            LocalDate startDate, LocalDate endDate, String description, boolean archived, int version) {
    }

    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped Project project, BusinessSummaryService.Profitability profitability) {
    }

    public record Request(String name, String code, String clientId, Boolean clearClient, String status, String currency, String budgetAmount,
            Boolean clearBudget, String startDate, String endDate, String description, Integer version) {
    }

    record Row(UUID id, String name, String code, UUID clientId, String clientName, String status, String currency, BigDecimal budgetAmount,
            LocalDate startDate, LocalDate endDate, String description, OffsetDateTime archivedAt, int version) {
    }

    private static final String SELECT = """
            select p.id, p.name, p.code, p.client_id, c.name as client_name, p.status, p.currency, p.budget_amount, p.start_date, p.end_date,
                   p.description, p.archived_at, p.version
            from projects p left join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id
            """;

    private final JdbcClient jdbc;
    private final FinanceAudit audit;
    private final BusinessSummaryService summaries;
    private final BusinessSettingsService settings;

    public ProjectService(JdbcClient jdbc, FinanceAudit audit, BusinessSummaryService summaries, BusinessSettingsService settings) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.summaries = summaries;
        this.settings = settings;
    }

    Row row(BusinessContext ctx, UUID id, boolean lock) {
        return jdbc.sql(SELECT + " where p.tenant_id = :t and p.id = :id" + (lock ? " for update of p" : ""))
                .param("t", ctx.tenantId()).param("id", id).query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    static Project project(Row r) {
        String cur = r.currency().trim();
        return new Project(r.id(), r.name(), r.code(), r.clientId(), r.clientName(), r.status(), cur,
                r.budgetAmount() == null ? null : Currencies.format(r.budgetAmount(), cur), r.startDate(), r.endDate(), r.description(), r.archivedAt() != null, r.version());
    }

    public Page<Project> list(BusinessContext ctx, String q, String status, UUID clientId, boolean includeArchived, Integer limit, Integer offset) {
        ctx.requireAll(Resource.PROJECTS, Action.READ);
        int lim = Page.limit(limit);
        int off = Page.offset(offset);
        StringBuilder where = new StringBuilder("p.tenant_id = :t");
        if (!includeArchived) {
            where.append(" and p.archived_at is null");
        }
        if (status != null && !status.isBlank()) {
            if (!STATUSES.contains(status.trim().toUpperCase(Locale.ROOT))) {
                throw Validate.invalid("status is not a valid choice");
            }
            where.append(" and p.status = :status");
        }
        if (clientId != null) {
            where.append(" and p.client_id = :client");
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append(" and (lower(p.name) like :q escape '\\' or lower(coalesce(p.code, '')) like :q escape '\\')");
        }
        var count = jdbc.sql("select count(*) from projects p where " + where).param("t", ctx.tenantId());
        var query = jdbc.sql(SELECT + " where " + where + " order by p.archived_at is not null, lower(p.name), p.id limit :lim offset :off")
                .param("t", ctx.tenantId()).param("lim", lim).param("off", off);
        if (status != null && !status.isBlank()) {
            count = count.param("status", status.trim().toUpperCase(Locale.ROOT));
            query = query.param("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (clientId != null) {
            count = count.param("client", clientId);
            query = query.param("client", clientId);
        }
        if (search) {
            count = count.param("q", BizSql.like(q));
            query = query.param("q", BizSql.like(q));
        }
        return new Page<>(query.query(Row.class).list().stream().map(ProjectService::project).toList(), count.query(Long.class).single(), lim, off);
    }

    public Detail get(BusinessContext ctx, UUID id) {
        ctx.requireAll(Resource.PROJECTS, Action.READ);
        Row r = row(ctx, id, false);
        return new Detail(project(r), summaries.profitability(ctx, r, null, null));
    }

    private void checkClient(BusinessContext ctx, UUID clientId) {
        boolean ok = jdbc.sql("select count(*) from clients where tenant_id = :t and id = :id and archived_at is null")
                .param("t", ctx.tenantId()).param("id", clientId).query(Long.class).single() > 0;
        if (!ok) {
            // Same answer for "does not exist", "belongs to another workspace" and "archived": the id leaks nothing.
            throw Validate.invalid("client_id does not refer to an active client");
        }
    }

    private static String status(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw Validate.invalid("status is not a valid choice");
        }
        return s;
    }

    @Transactional
    public Detail create(BusinessContext ctx, Request req) {
        ctx.requireAll(Resource.PROJECTS, Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 120, true);
        String currency = req.currency() == null ? settings.defaults(ctx).baseCurrency().trim() : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        UUID client = Validate.uuid("client_id", req.clientId(), false);
        if (client != null) {
            checkClient(ctx, client);
        }
        BigDecimal budget = req.budgetAmount() == null ? null : Amounts.parse("budget_amount", req.budgetAmount(), currency, Amounts.Sign.POSITIVE);
        LocalDate start = Validate.date("start_date", req.startDate(), false);
        LocalDate end = Validate.date("end_date", req.endDate(), false);
        if (start != null && end != null && end.isBefore(start)) {
            throw Validate.invalid("end_date cannot be before start_date");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into projects (id, tenant_id, name, code, client_id, status, currency, budget_amount, start_date, end_date, description, created_by, created_at, updated_at)
                    values (:id, :t, :n, :code, :c, :s, :cur, :b, :sd, :ed, :d, :u, :now, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("n", name).param("code", Validate.text("code", req.code(), 1, 30, false))
                    .param("c", client).param("s", status(req.status(), "ACTIVE")).param("cur", currency).param("b", budget).param("sd", start).param("ed", end)
                    .param("d", Validate.text("description", req.description(), 1, 1000, false)).param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A project with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "PROJECT_CREATED", "PROJECT", id, budget == null ? null : "budget " + Currencies.format(budget, currency) + " " + currency);
        return get(ctx, id);
    }

    private long linked(BusinessContext ctx, UUID id, String table) {
        return jdbc.sql("select count(*) from " + table + " where tenant_id = :t and project_id = :p").param("t", ctx.tenantId()).param("p", id)
                .query(Long.class).single();
    }

    @Transactional
    public Detail update(BusinessContext ctx, UUID id, Request req) {
        ctx.requireAll(Resource.PROJECTS, Action.UPDATE);
        Row r = row(ctx, id, true);
        if (Validate.version(req.version()) != r.version()) {
            throw new ApiException(ErrorCode.CONFLICT, "This project was changed elsewhere. Reload and try again.");
        }
        if (r.archivedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Restore the project before editing it");
        }
        List<String> changed = new ArrayList<>();
        String currentCurrency = r.currency().trim();
        String currency = req.currency() == null ? currentCurrency : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!currency.equals(currentCurrency)) {
            if (!Currencies.supported(currency)) {
                throw Validate.invalid("currency is not supported");
            }
            if (linked(ctx, id, "business_expenses") + linked(ctx, id, "invoices") > 0) {
                throw new ApiException(ErrorCode.CONFLICT, "The currency cannot change once expenses or invoices are linked to the project");
            }
            changed.add("currency");
        }
        UUID client = r.clientId();
        if (Boolean.TRUE.equals(req.clearClient())) {
            client = null;
        } else if (req.clientId() != null) {
            client = Validate.uuid("client_id", req.clientId(), true);
        }
        if (!java.util.Objects.equals(client, r.clientId())) {
            if (client != null) {
                checkClient(ctx, client);
            }
            if (linked(ctx, id, "invoices") > 0) {
                throw new ApiException(ErrorCode.CONFLICT, "The client cannot change once invoices are linked to the project");
            }
            changed.add("client");
        }
        BigDecimal budget = r.budgetAmount();
        if (Boolean.TRUE.equals(req.clearBudget())) {
            budget = null;
        } else if (req.budgetAmount() != null) {
            budget = Amounts.parse("budget_amount", req.budgetAmount(), currency, Amounts.Sign.POSITIVE);
        } else if (budget != null && !currency.equals(currentCurrency)) {
            budget = null;
        }
        String status = status(req.status(), r.status());
        LocalDate start = req.startDate() == null ? r.startDate() : Validate.date("start_date", req.startDate(), false);
        LocalDate end = req.endDate() == null ? r.endDate() : Validate.date("end_date", req.endDate(), false);
        if (start != null && end != null && end.isBefore(start)) {
            throw Validate.invalid("end_date cannot be before start_date");
        }
        if (!status.equals(r.status())) {
            changed.add("status " + r.status() + "->" + status);
        }
        if (!java.util.Objects.equals(budget, r.budgetAmount())) {
            changed.add("budget");
        }
        try {
            jdbc.sql("""
                    update projects set name = :n, code = :code, client_id = :c, status = :s, currency = :cur, budget_amount = :b, start_date = :sd, end_date = :ed,
                           description = :d, version = version + 1, updated_at = :now where tenant_id = :t and id = :id
                    """).param("n", req.name() == null ? r.name() : Validate.text("name", req.name(), 1, 120, true))
                    .param("code", req.code() == null ? r.code() : Validate.text("code", req.code(), 1, 30, false)).param("c", client).param("s", status)
                    .param("cur", currency).param("b", budget).param("sd", start).param("ed", end)
                    .param("d", req.description() == null ? r.description() : Validate.text("description", req.description(), 1, 1000, false))
                    .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A project with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "PROJECT_UPDATED", "PROJECT", id, "changed: " + changed);
        return get(ctx, id);
    }

    @Transactional
    public Detail setArchived(BusinessContext ctx, UUID id, boolean archived) {
        ctx.requireAll(Resource.PROJECTS, Action.ARCHIVE);
        row(ctx, id, true);
        try {
            jdbc.sql("update projects set archived_at = " + (archived ? ":now" : "null") + ", version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                    .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "An active project with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), archived ? "PROJECT_ARCHIVED" : "PROJECT_RESTORED", "PROJECT", id, null);
        return get(ctx, id);
    }
}
