package com.finora.finance.business;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clients and vendors. They share one implementation and differ only by table. GSTIN is checked for FORMAT only (15
 * characters, state code, PAN shape); it is not verified against any government record and no compliance is claimed.
 * Partial updates: a field that is absent is unchanged and an empty string clears it.
 */
@Service
public class PartyService {

    public enum Kind {
        CLIENT("clients", "client"), VENDOR("vendors", "vendor");

        final String table;
        final String noun;

        Kind(String table, String noun) {
            this.table = table;
            this.noun = noun;
        }
    }

    public record Party(UUID id, String name, String email, String phone, String gstin, String address, String notes, Integer paymentTermsDays,
            boolean archived, int version) {
    }

    public record Money(String currency, String amount) {
    }

    public record ClientBalance(String currency, String invoiced, String paid, String outstanding, String overdue) {
    }

    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped Party party, List<ClientBalance> balances, List<Money> unallocatedPayments, List<Money> expenses, long invoiceCount) {
    }

    public record Request(String name, String email, String phone, String gstin, String address, String notes, Integer paymentTermsDays, Integer version) {
    }

    private record Row(UUID id, String name, String email, String phone, String gstin, String address, String notes, Integer paymentTermsDays,
            java.time.OffsetDateTime archivedAt, int version) {
    }

    private record Bal(String currency, BigDecimal invoiced, BigDecimal paid, BigDecimal outstanding, BigDecimal overdue) {
    }

    private record Sum(String currency, BigDecimal amount) {
    }

    private static final Pattern GSTIN = Pattern.compile("^[0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public PartyService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private String cols(Kind kind) {
        return "id, name, email, phone, gstin, address, notes, " + (kind == Kind.CLIENT ? "payment_terms_days" : "null::integer as payment_terms_days")
                + ", archived_at, version";
    }

    private Row row(BusinessContext ctx, Kind kind, UUID id, boolean lock) {
        return jdbc.sql("select " + cols(kind) + " from " + kind.table + " where tenant_id = :t and id = :id" + (lock ? " for update" : ""))
                .param("t", ctx.tenantId()).param("id", id).query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private static Party party(Row r) {
        return new Party(r.id(), r.name(), r.email(), r.phone(), r.gstin(), r.address(), r.notes(), r.paymentTermsDays(), r.archivedAt() != null, r.version());
    }

    public Page<Party> list(BusinessContext ctx, Kind kind, String q, boolean includeArchived, Integer limit, Integer offset) {
        ctx.requireAll(Resource.CLIENTS_VENDORS, Action.READ);
        int lim = Page.limit(limit);
        int off = Page.offset(offset);
        StringBuilder where = new StringBuilder("tenant_id = :t");
        if (!includeArchived) {
            where.append(" and archived_at is null");
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(name) like :q escape '\\' or lower(coalesce(email, '')) like :q escape '\\')");
        }
        var count = jdbc.sql("select count(*) from " + kind.table + " where " + where).param("t", ctx.tenantId());
        var query = jdbc.sql("select " + cols(kind) + " from " + kind.table + " where " + where + " order by archived_at is not null, lower(name), id limit :lim offset :off")
                .param("t", ctx.tenantId()).param("lim", lim).param("off", off);
        if (q != null && !q.isBlank()) {
            count = count.param("q", BizSql.like(q));
            query = query.param("q", BizSql.like(q));
        }
        return new Page<>(query.query(Row.class).list().stream().map(PartyService::party).toList(), count.query(Long.class).single(), lim, off);
    }

    public Detail get(BusinessContext ctx, Kind kind, UUID id) {
        ctx.requireAll(Resource.CLIENTS_VENDORS, Action.READ);
        Party p = party(row(ctx, kind, id, false));
        if (kind == Kind.VENDOR) {
            List<Money> expenses = jdbc.sql("select currency, sum(amount) as amount from business_expenses where tenant_id = :t and vendor_id = :v and status = 'POSTED' group by currency order by currency")
                    .param("t", ctx.tenantId()).param("v", id).query(Sum.class).list().stream().map(s -> new Money(s.currency().trim(), Currencies.format(s.amount(), s.currency().trim()))).toList();
            return new Detail(p, null, null, expenses, 0);
        }
        List<ClientBalance> balances = jdbc.sql("""
                select i.currency,
                       coalesce(sum(i.total), 0) as invoiced,
                       coalesce(sum(pd.paid), 0) as paid,
                       coalesce(sum(i.total - pd.paid), 0) as outstanding,
                       coalesce(sum(case when i.due_date < :today and i.total - pd.paid > 0 then i.total - pd.paid else 0 end), 0) as overdue
                from invoices i
                """ + BizSql.PAID_LATERAL + """
                where i.tenant_id = :t and i.client_id = :c and i.status = 'ISSUED' group by i.currency order by i.currency
                """).param("t", ctx.tenantId()).param("c", id).param("today", ctx.today()).query(Bal.class).list().stream().map(b -> {
                    String c = b.currency().trim();
                    return new ClientBalance(c, Currencies.format(b.invoiced(), c), Currencies.format(b.paid(), c), Currencies.format(b.outstanding(), c),
                            Currencies.format(b.overdue(), c));
                }).toList();
        List<Money> credit = jdbc.sql("""
                select p.currency, sum(p.amount - coalesce((select sum(a.amount) from payment_allocations a where a.tenant_id = p.tenant_id and a.payment_id = p.id), 0)) as amount
                from payments p where p.tenant_id = :t and p.client_id = :c and p.status = 'RECORDED' group by p.currency order by p.currency
                """).param("t", ctx.tenantId()).param("c", id).query(Sum.class).list().stream()
                .filter(s -> s.amount().signum() > 0).map(s -> new Money(s.currency().trim(), Currencies.format(s.amount(), s.currency().trim()))).toList();
        long invoices = jdbc.sql("select count(*) from invoices where tenant_id = :t and client_id = :c and status = 'ISSUED'")
                .param("t", ctx.tenantId()).param("c", id).query(Long.class).single();
        return new Detail(p, balances, credit, null, invoices);
    }

    private static String email(String v) {
        String e = Validate.text("email", v, 3, 120, false);
        if (e != null && !EMAIL.matcher(e).matches()) {
            throw Validate.invalid("email must look like name@example.com");
        }
        return e;
    }

    private static String gstin(String v) {
        String g = Validate.text("gstin", v, 15, 15, false);
        if (g == null) {
            return null;
        }
        g = g.toUpperCase(java.util.Locale.ROOT);
        if (!GSTIN.matcher(g).matches()) {
            throw Validate.invalid("gstin must be 15 characters in the GSTIN format (format check only; it is not verified)");
        }
        return g;
    }

    private static Integer terms(Integer v) {
        if (v != null && (v < 0 || v > 365)) {
            throw Validate.invalid("payment_terms_days must be between 0 and 365");
        }
        return v;
    }

    @Transactional
    public Detail create(BusinessContext ctx, Kind kind, Request req) {
        ctx.requireAll(Resource.CLIENTS_VENDORS, Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 120, true);
        UUID id = UUID.randomUUID();
        try {
            if (kind == Kind.CLIENT) {
                jdbc.sql("""
                        insert into clients (id, tenant_id, name, email, phone, gstin, address, notes, payment_terms_days, created_by, created_at, updated_at)
                        values (:id, :t, :n, :e, :p, :g, :a, :no, :terms, :u, :now, :now)
                        """).param("terms", terms(req.paymentTermsDays()))
                        .param("id", id).param("t", ctx.tenantId()).param("n", name).param("e", email(req.email())).param("p", Validate.text("phone", req.phone(), 3, 30, false))
                        .param("g", gstin(req.gstin())).param("a", Validate.text("address", req.address(), 1, 500, false)).param("no", Validate.text("notes", req.notes(), 1, 1000, false))
                        .param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
            } else {
                jdbc.sql("""
                        insert into vendors (id, tenant_id, name, email, phone, gstin, address, notes, created_by, created_at, updated_at)
                        values (:id, :t, :n, :e, :p, :g, :a, :no, :u, :now, :now)
                        """).param("id", id).param("t", ctx.tenantId()).param("n", name).param("e", email(req.email())).param("p", Validate.text("phone", req.phone(), 3, 30, false))
                        .param("g", gstin(req.gstin())).param("a", Validate.text("address", req.address(), 1, 500, false)).param("no", Validate.text("notes", req.notes(), 1, 1000, false))
                        .param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A " + kind.noun + " with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), kind.name() + "_CREATED", kind.name(), id, null);
        return get(ctx, kind, id);
    }

    /** Empty string clears, null leaves unchanged. */
    private static String patch(String field, String incoming, String current, int max) {
        if (incoming == null) {
            return current;
        }
        return Validate.text(field, incoming, 1, max, false);
    }

    @Transactional
    public Detail update(BusinessContext ctx, Kind kind, UUID id, Request req) {
        ctx.requireAll(Resource.CLIENTS_VENDORS, Action.UPDATE);
        Row r = row(ctx, kind, id, true);
        if (Validate.version(req.version()) != r.version()) {
            throw new ApiException(ErrorCode.CONFLICT, "This " + kind.noun + " was changed elsewhere. Reload and try again.");
        }
        if (r.archivedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Restore the " + kind.noun + " before editing it");
        }
        List<String> changed = new ArrayList<>();
        String name = req.name() == null ? r.name() : Validate.text("name", req.name(), 1, 120, true);
        String email = req.email() == null ? r.email() : email(req.email());
        String phone = patch("phone", req.phone(), r.phone(), 30);
        String gst = req.gstin() == null ? r.gstin() : gstin(req.gstin());
        String address = patch("address", req.address(), r.address(), 500);
        String notes = patch("notes", req.notes(), r.notes(), 1000);
        Integer terms = kind == Kind.CLIENT && req.paymentTermsDays() != null ? terms(req.paymentTermsDays()) : r.paymentTermsDays();
        if (!name.equals(r.name())) {
            changed.add("name");
        }
        if (!java.util.Objects.equals(gst, r.gstin())) {
            // Invoices are frozen at issue, so a changed GSTIN never alters an issued invoice (BF-CLI-1); this only records it.
            changed.add("gstin");
        }
        try {
            var update = jdbc.sql("""
                    update %s set name = :n, email = :e, phone = :p, gstin = :g, address = :a, notes = :no, %s version = version + 1, updated_at = :now
                    where tenant_id = :t and id = :id
                    """.formatted(kind.table, kind == Kind.CLIENT ? "payment_terms_days = :terms," : ""))
                    .param("n", name).param("e", email).param("p", phone).param("g", gst).param("a", address).param("no", notes)
                    .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id);
            if (kind == Kind.CLIENT) {
                update = update.param("terms", terms, java.sql.Types.INTEGER);
            }
            update.update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A " + kind.noun + " with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), kind.name() + "_UPDATED", kind.name(), id, "fields changed: " + changed);
        return get(ctx, kind, id);
    }

    @Transactional
    public Detail setArchived(BusinessContext ctx, Kind kind, UUID id, boolean archived) {
        ctx.requireAll(Resource.CLIENTS_VENDORS, Action.ARCHIVE);
        Row r = row(ctx, kind, id, true);
        if (archived && r.archivedAt() == null && kind == Kind.CLIENT) {
            long open = jdbc.sql("""
                    select count(*) from invoices i
                    """ + BizSql.PAID_LATERAL + """
                    where i.tenant_id = :t and i.client_id = :c and i.status = 'ISSUED' and i.total - pd.paid > 0
                    """).param("t", ctx.tenantId()).param("c", id).query(Long.class).single();
            if (open > 0) {
                throw new ApiException(ErrorCode.CONFLICT, "This client still has unpaid invoices");
            }
        }
        try {
            jdbc.sql("update " + kind.table + " set archived_at = " + (archived ? ":now" : "null") + ", version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                    .param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "An active " + kind.noun + " with this name already exists");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), kind.name() + (archived ? "_ARCHIVED" : "_RESTORED"), kind.name(), id, null);
        return get(ctx, kind, id);
    }
}
