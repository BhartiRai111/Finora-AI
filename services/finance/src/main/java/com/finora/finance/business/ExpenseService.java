package com.finora.finance.business;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.authz.Scope;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business expenses (cash/incurred basis: an expense counts when it is posted; there is no payables workflow yet). An
 * expense may link a vendor, a project and a category. If it links a project, the currencies must match: nothing is ever
 * converted. A voided expense stays visible but is excluded from every total. Contributors (matrix scope OWN) see and
 * change only the expenses they created.
 */
@Service
public class ExpenseService {

    public record Expense(UUID id, LocalDate expenseDate, String amount, String currency, UUID vendorId, String vendorName, UUID projectId,
            String projectName, UUID categoryId, String categoryName, String description, String reference, String status, String voidReason, int version) {
    }

    public record Request(String expenseDate, String amount, String currency, String vendorId, Boolean clearVendor, String projectId, Boolean clearProject,
            String categoryId, Boolean clearCategory, String description, String reference, Integer version) {
    }

    public record VoidRequest(String reason, Integer version) {
    }

    public record Category(UUID id, String name, boolean archived, int version) {
    }

    public record CategoryRequest(String name, Integer version) {
    }

    private record Row(UUID id, LocalDate expenseDate, BigDecimal amount, String currency, UUID vendorId, String vendorName, UUID projectId, String projectName,
            UUID categoryId, String categoryName, String description, String reference, String status, String voidReason, int version, UUID createdBy) {
    }

    private record CatRow(UUID id, String name, java.time.OffsetDateTime archivedAt, int version) {
    }

    private static final String SELECT = """
            select e.id, e.expense_date, e.amount, e.currency, e.vendor_id, v.name as vendor_name, e.project_id, p.name as project_name,
                   e.category_id, c.name as category_name, e.description, e.reference, e.status, e.void_reason, e.version, e.created_by
            from business_expenses e
            left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id
            left join projects p on p.tenant_id = e.tenant_id and p.id = e.project_id
            left join expense_categories c on c.tenant_id = e.tenant_id and c.id = e.category_id
            """;

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public ExpenseService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private Row row(BusinessContext ctx, UUID id, Scope scope, boolean lock) {
        Row r = jdbc.sql(SELECT + " where e.tenant_id = :t and e.id = :id" + (lock ? " for update of e" : "")).param("t", ctx.tenantId()).param("id", id)
                .query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        if (scope == Scope.OWN && !r.createdBy().equals(ctx.userId())) {
            // Another person's expense looks exactly like one that does not exist.
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        return r;
    }

    private static Expense view(Row r) {
        String c = r.currency().trim();
        return new Expense(r.id(), r.expenseDate(), Currencies.format(r.amount(), c), c, r.vendorId(), r.vendorName(), r.projectId(), r.projectName(), r.categoryId(),
                r.categoryName(), r.description(), r.reference(), r.status(), r.voidReason(), r.version());
    }

    public Page<Expense> list(BusinessContext ctx, String from, String to, UUID vendorId, UUID projectId, UUID categoryId, String q, boolean includeVoided,
            Integer limit, Integer offset) {
        Scope scope = ctx.scope(Resource.TRANSACTIONS, Action.READ);
        int lim = Page.limit(limit);
        int off = Page.offset(offset);
        LocalDate f = Validate.date("from", from, false);
        LocalDate t = Validate.date("to", to, false);
        StringBuilder where = new StringBuilder("e.tenant_id = :t");
        if (scope == Scope.OWN) {
            where.append(" and e.created_by = :u");
        }
        if (!includeVoided) {
            where.append(" and e.status = 'POSTED'");
        }
        if (f != null) {
            where.append(" and e.expense_date >= :from");
        }
        if (t != null) {
            where.append(" and e.expense_date <= :to");
        }
        if (vendorId != null) {
            where.append(" and e.vendor_id = :vendor");
        }
        if (projectId != null) {
            where.append(" and e.project_id = :project");
        }
        if (categoryId != null) {
            where.append(" and e.category_id = :category");
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append(" and (lower(e.description) like :q escape '\\' or lower(coalesce(e.reference, '')) like :q escape '\\' or lower(coalesce(v.name, '')) like :q escape '\\')");
        }
        var count = jdbc.sql("select count(*) from business_expenses e left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id where " + where).param("t", ctx.tenantId());
        var query = jdbc.sql(SELECT + " where " + where + " order by e.expense_date desc, e.created_at desc, e.id limit :lim offset :off")
                .param("t", ctx.tenantId()).param("lim", lim).param("off", off);
        if (scope == Scope.OWN) {
            count = count.param("u", ctx.userId());
            query = query.param("u", ctx.userId());
        }
        if (f != null) {
            count = count.param("from", f);
            query = query.param("from", f);
        }
        if (t != null) {
            count = count.param("to", t);
            query = query.param("to", t);
        }
        if (vendorId != null) {
            count = count.param("vendor", vendorId);
            query = query.param("vendor", vendorId);
        }
        if (projectId != null) {
            count = count.param("project", projectId);
            query = query.param("project", projectId);
        }
        if (categoryId != null) {
            count = count.param("category", categoryId);
            query = query.param("category", categoryId);
        }
        if (search) {
            count = count.param("q", BizSql.like(q));
            query = query.param("q", BizSql.like(q));
        }
        return new Page<>(query.query(Row.class).list().stream().map(ExpenseService::view).toList(), count.query(Long.class).single(), lim, off);
    }

    public Expense get(BusinessContext ctx, UUID id) {
        Scope scope = ctx.scope(Resource.TRANSACTIONS, Action.READ);
        return view(row(ctx, id, scope, false));
    }

    private void checkVendor(BusinessContext ctx, UUID id) {
        if (jdbc.sql("select count(*) from vendors where tenant_id = :t and id = :id and archived_at is null").param("t", ctx.tenantId()).param("id", id)
                .query(Long.class).single() == 0) {
            throw Validate.invalid("vendor_id does not refer to an active vendor");
        }
    }

    private void checkCategory(BusinessContext ctx, UUID id) {
        if (jdbc.sql("select count(*) from expense_categories where tenant_id = :t and id = :id and archived_at is null").param("t", ctx.tenantId()).param("id", id)
                .query(Long.class).single() == 0) {
            throw Validate.invalid("category_id does not refer to an active category");
        }
    }

    private void checkProject(BusinessContext ctx, UUID id, String currency) {
        var p = jdbc.sql("select status, currency, archived_at is not null as archived from projects where tenant_id = :t and id = :id")
                .param("t", ctx.tenantId()).param("id", id).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2).trim(), rs.getString(3)}).optional();
        if (p.isEmpty()) {
            throw Validate.invalid("project_id does not refer to an active project");
        }
        if ("t".equals(p.get()[2]) || "true".equals(p.get()[2]) || !ProjectService.OPEN.contains(p.get()[0])) {
            throw new ApiException(ErrorCode.CONFLICT, "The project is closed or archived and accepts no new expenses");
        }
        if (!p.get()[1].equals(currency)) {
            throw Validate.invalid("The expense currency must match the project currency (" + p.get()[1] + "); nothing is converted");
        }
    }

    private LocalDate expenseDate(BusinessContext ctx, String raw) {
        LocalDate d = Validate.date("expense_date", raw, true);
        if (d.isAfter(ctx.today())) {
            throw Validate.invalid("expense_date cannot be in the future");
        }
        return d;
    }

    @Transactional
    public Expense create(BusinessContext ctx, Request req) {
        ctx.scope(Resource.TRANSACTIONS, Action.CREATE);
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal amount = Amounts.parse("amount", req.amount(), currency, Amounts.Sign.POSITIVE);
        LocalDate date = expenseDate(ctx, req.expenseDate());
        UUID vendor = Validate.uuid("vendor_id", req.vendorId(), false);
        UUID project = Validate.uuid("project_id", req.projectId(), false);
        UUID category = Validate.uuid("category_id", req.categoryId(), false);
        if (vendor != null) {
            checkVendor(ctx, vendor);
        }
        if (project != null) {
            checkProject(ctx, project, currency);
        }
        if (category != null) {
            checkCategory(ctx, category);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into business_expenses (id, tenant_id, expense_date, amount, currency, vendor_id, project_id, category_id, description, reference, created_by, created_at, updated_at)
                    values (:id, :t, :d, :a, :c, :v, :p, :cat, :desc, :ref, :u, :now, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("d", date).param("a", amount).param("c", currency).param("v", vendor).param("p", project)
                    .param("cat", category).param("desc", Validate.text("description", req.description(), 1, 300, true))
                    .param("ref", Validate.text("reference", req.reference(), 1, 60, false)).param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "This vendor bill reference is already recorded");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_CREATED", "EXPENSE", id, Currencies.format(amount, currency) + " " + currency);
        return view(row(ctx, id, ctx.scope(Resource.TRANSACTIONS, Action.READ), false));
    }

    @Transactional
    public Expense update(BusinessContext ctx, UUID id, Request req) {
        Scope scope = ctx.scope(Resource.TRANSACTIONS, Action.UPDATE);
        Row r = row(ctx, id, scope, true);
        if (Validate.version(req.version()) != r.version()) {
            throw new ApiException(ErrorCode.CONFLICT, "This expense was changed elsewhere. Reload and try again.");
        }
        if (!"POSTED".equals(r.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "A voided expense cannot be edited");
        }
        String currency = r.currency().trim();
        if (req.currency() != null && !req.currency().trim().equalsIgnoreCase(currency)) {
            throw Validate.invalid("The currency of an expense cannot change; void it and record a new one");
        }
        BigDecimal amount = req.amount() == null ? r.amount() : Amounts.parse("amount", req.amount(), currency, Amounts.Sign.POSITIVE);
        LocalDate date = req.expenseDate() == null ? r.expenseDate() : expenseDate(ctx, req.expenseDate());
        UUID vendor = Boolean.TRUE.equals(req.clearVendor()) ? null : req.vendorId() == null ? r.vendorId() : Validate.uuid("vendor_id", req.vendorId(), true);
        UUID project = Boolean.TRUE.equals(req.clearProject()) ? null : req.projectId() == null ? r.projectId() : Validate.uuid("project_id", req.projectId(), true);
        UUID category = Boolean.TRUE.equals(req.clearCategory()) ? null : req.categoryId() == null ? r.categoryId() : Validate.uuid("category_id", req.categoryId(), true);
        List<String> changed = new ArrayList<>();
        if (vendor != null && !vendor.equals(r.vendorId())) {
            checkVendor(ctx, vendor);
        }
        if (project != null && !project.equals(r.projectId())) {
            checkProject(ctx, project, currency);
        }
        if (category != null && !category.equals(r.categoryId())) {
            checkCategory(ctx, category);
        }
        if (amount.compareTo(r.amount()) != 0) {
            changed.add("amount " + Currencies.format(r.amount(), currency) + "->" + Currencies.format(amount, currency));
        }
        if (!date.equals(r.expenseDate())) {
            changed.add("expense_date");
        }
        if (!java.util.Objects.equals(project, r.projectId())) {
            changed.add("project");
        }
        if (!java.util.Objects.equals(vendor, r.vendorId())) {
            changed.add("vendor");
        }
        if (!java.util.Objects.equals(category, r.categoryId())) {
            changed.add("category");
        }
        try {
            jdbc.sql("""
                    update business_expenses set expense_date = :d, amount = :a, vendor_id = :v, project_id = :p, category_id = :cat, description = :desc, reference = :ref,
                           version = version + 1, updated_at = :now where tenant_id = :t and id = :id
                    """).param("d", date).param("a", amount).param("v", vendor).param("p", project).param("cat", category)
                    .param("desc", req.description() == null ? r.description() : Validate.text("description", req.description(), 1, 300, true))
                    .param("ref", req.reference() == null ? r.reference() : Validate.text("reference", req.reference(), 1, 60, false))
                    .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "This vendor bill reference is already recorded");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_UPDATED", "EXPENSE", id, "changed: " + changed);
        return view(row(ctx, id, scope, false));
    }

    @Transactional
    public Expense voidExpense(BusinessContext ctx, UUID id, VoidRequest req) {
        Scope scope = ctx.scope(Resource.TRANSACTIONS, Action.ARCHIVE);
        Row r = row(ctx, id, scope, true);
        String reason = Validate.text("reason", req.reason(), 1, 200, true);
        if (!"POSTED".equals(r.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The expense is already voided");
        }
        if (req.version() != null && req.version() != r.version()) {
            throw new ApiException(ErrorCode.CONFLICT, "This expense was changed elsewhere. Reload and try again.");
        }
        jdbc.sql("update business_expenses set status = 'VOIDED', void_reason = :r, voided_at = :now, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("r", reason).param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_VOIDED", "EXPENSE", id, null);
        return view(row(ctx, id, scope, false));
    }

    // ---- categories ----

    private static Category category(CatRow r) {
        return new Category(r.id(), r.name(), r.archivedAt() != null, r.version());
    }

    public List<Category> categories(BusinessContext ctx, boolean includeArchived) {
        ctx.scope(Resource.TRANSACTIONS, Action.READ);
        return jdbc.sql("select id, name, archived_at, version from expense_categories where tenant_id = :t " + (includeArchived ? "" : "and archived_at is null ")
                + "order by archived_at is not null, lower(name), id").param("t", ctx.tenantId()).query(CatRow.class).list().stream().map(ExpenseService::category).toList();
    }

    @Transactional
    public Category createCategory(BusinessContext ctx, CategoryRequest req) {
        ctx.requireAll(Resource.TRANSACTIONS, Action.CREATE);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into expense_categories (id, tenant_id, name, created_at, updated_at) values (:id, :t, :n, :now, :now)").param("id", id).param("t", ctx.tenantId())
                    .param("n", Validate.text("name", req.name(), 1, 80, true)).param("now", BizSql.now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_CATEGORY_CREATED", "EXPENSE_CATEGORY", id, null);
        return category(jdbc.sql("select id, name, archived_at, version from expense_categories where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id).query(CatRow.class).single());
    }

    @Transactional
    public Category updateCategory(BusinessContext ctx, UUID id, CategoryRequest req, Boolean archive) {
        ctx.requireAll(Resource.TRANSACTIONS, archive == null ? Action.UPDATE : Action.ARCHIVE);
        CatRow r = jdbc.sql("select id, name, archived_at, version from expense_categories where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", id)
                .query(CatRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        try {
            if (archive != null) {
                jdbc.sql("update expense_categories set archived_at = " + (archive ? ":now" : "null") + ", version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                        .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
            } else {
                if (Validate.version(req.version()) != r.version()) {
                    throw new ApiException(ErrorCode.CONFLICT, "This category was changed elsewhere. Reload and try again.");
                }
                jdbc.sql("update expense_categories set name = :n, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                        .param("n", Validate.text("name", req.name(), 1, 80, true)).param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_CATEGORY_" + (archive == null ? "RENAMED" : archive ? "ARCHIVED" : "RESTORED"), "EXPENSE_CATEGORY", id, null);
        return category(jdbc.sql("select id, name, archived_at, version from expense_categories where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id).query(CatRow.class).single());
    }
}
