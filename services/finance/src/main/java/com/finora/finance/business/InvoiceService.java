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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoices. Lifecycle: DRAFT (freely editable, no number) -> ISSUED (number assigned, content frozen) -> CANCELLED (terminal);
 * a DRAFT can also be cancelled (discarded). Payment status is never stored: it is derived on every read from the
 * payments that still stand, so outstanding balance and overdue status are always deterministic and consistent.
 * Overdue = issued, outstanding > 0 and due date earlier than today in the business time zone (it flips at the business's
 * midnight; the due date itself is not overdue).
 */
@Service
public class InvoiceService {

    static final Set<String> FILTERS = Set.of("DRAFT", "ISSUED", "UNPAID", "PARTIALLY_PAID", "PAID", "OVERDUE", "CANCELLED");
    private static final Pattern QUANTITY = Pattern.compile("^\\d{1,9}(\\.\\d{1,4})?$");
    private static final Pattern PERCENT = Pattern.compile("^\\d{1,3}(\\.\\d{1,2})?$");
    private static final int MAX_LINES = 100;

    public record LineRequest(String description, String quantity, String unitPrice, String discount, String taxPercent) {
    }

    public record CreateRequest(String clientId, String projectId, String invoiceDate, Integer termsDays, String dueDate, String currency, String notes,
            List<LineRequest> lines) {
    }

    public record UpdateRequest(String projectId, Boolean clearProject, String invoiceDate, Integer termsDays, String dueDate, String notes,
            List<LineRequest> lines, Integer version) {
    }

    public record IssueRequest(Boolean confirm, String expectedTotal) {
    }

    public record CancelRequest(Boolean confirm, String reason) {
    }

    public record Line(int position, String description, String quantity, String unitPrice, String grossAmount, String discount, String netAmount,
            String taxPercent, String taxAmount, String lineTotal) {
    }

    public record PaymentLink(UUID paymentId, LocalDate paymentDate, String amount, String method, String reference, String paymentStatus) {
    }

    public record Invoice(UUID id, String number, String status, String displayStatus, boolean overdue, long daysOverdue, UUID clientId, String clientName,
            UUID projectId, String projectName, LocalDate invoiceDate, LocalDate dueDate, String currency, String subtotal, String discountTotal,
            String taxTotal, String total, String paid, String outstanding, String notes, OffsetDateTime issuedAt, OffsetDateTime cancelledAt,
            String cancelReason, int version, List<Line> lines, List<PaymentLink> payments) {
    }

    record Row(UUID id, String number, String status, UUID clientId, String clientName, UUID projectId, String projectName, LocalDate invoiceDate,
            LocalDate dueDate, String currency, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total, BigDecimal paid,
            String notes, OffsetDateTime issuedAt, OffsetDateTime cancelledAt, String cancelReason, int version) {
    }

    private record LineRow(int position, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossAmount, BigDecimal discount,
            BigDecimal netAmount, BigDecimal taxPercent, BigDecimal taxAmount, BigDecimal lineTotal) {
    }

    private record PayRow(UUID paymentId, LocalDate paymentDate, BigDecimal amount, String method, String reference, String status) {
    }

    private record Computed(List<LineRow> rows, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
    }

    private static final String SELECT = """
            select i.id, i.number, i.status, i.client_id, c.name as client_name, i.project_id, p.name as project_name, i.invoice_date, i.due_date, i.currency,
                   i.subtotal, i.discount_total, i.tax_total, i.total, coalesce(pd.paid, 0) as paid, i.notes, i.issued_at, i.cancelled_at, i.cancel_reason, i.version
            from invoices i
            join clients c on c.tenant_id = i.tenant_id and c.id = i.client_id
            left join projects p on p.tenant_id = i.tenant_id and p.id = i.project_id
            """ + BizSql.PAID_LATERAL;

    private final JdbcClient jdbc;
    private final FinanceAudit audit;
    private final BusinessSettingsService settings;

    public InvoiceService(JdbcClient jdbc, FinanceAudit audit, BusinessSettingsService settings) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.settings = settings;
    }

    Row row(BusinessContext ctx, UUID id, boolean lock) {
        if (lock) {
            // Serialises with payments, issue and cancel on this invoice; the joined view is read after the lock is held.
            jdbc.sql("select 1 from invoices where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", id).query(Integer.class).optional()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        }
        return jdbc.sql(SELECT + " where i.tenant_id = :t and i.id = :id").param("t", ctx.tenantId()).param("id", id).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    static BigDecimal outstanding(Row r) {
        return "ISSUED".equals(r.status()) ? r.total().subtract(r.paid()).max(BigDecimal.ZERO) : BigDecimal.ZERO;
    }

    static boolean overdue(Row r, LocalDate today) {
        return "ISSUED".equals(r.status()) && outstanding(r).signum() > 0 && r.dueDate().isBefore(today);
    }

    static String displayStatus(Row r, LocalDate today) {
        if ("DRAFT".equals(r.status()) || "CANCELLED".equals(r.status())) {
            return r.status();
        }
        if (outstanding(r).signum() == 0) {
            return "PAID";
        }
        if (overdue(r, today)) {
            return "OVERDUE";
        }
        return r.paid().signum() > 0 ? "PARTIALLY_PAID" : "UNPAID";
    }

    private Invoice view(BusinessContext ctx, Row r, boolean detail) {
        String cur = r.currency().trim();
        LocalDate today = ctx.today();
        long days = overdue(r, today) ? ChronoUnit.DAYS.between(r.dueDate(), today) : 0;
        List<Line> lines = null;
        List<PaymentLink> payments = null;
        if (detail) {
            lines = jdbc.sql("select position, description, quantity, unit_price, gross_amount, discount, net_amount, tax_percent, tax_amount, line_total from invoice_lines where tenant_id = :t and invoice_id = :i order by position")
                    .param("t", ctx.tenantId()).param("i", r.id()).query(LineRow.class).list().stream()
                    .map(l -> new Line(l.position(), l.description(), l.quantity().stripTrailingZeros().toPlainString(), Currencies.format(l.unitPrice(), cur),
                            Currencies.format(l.grossAmount(), cur), Currencies.format(l.discount(), cur), Currencies.format(l.netAmount(), cur),
                            l.taxPercent().toPlainString(), Currencies.format(l.taxAmount(), cur), Currencies.format(l.lineTotal(), cur))).toList();
            payments = jdbc.sql("""
                    select p.id as payment_id, p.payment_date, a.amount, p.method, p.reference, p.status
                    from payment_allocations a join payments p on p.tenant_id = a.tenant_id and p.id = a.payment_id
                    where a.tenant_id = :t and a.invoice_id = :i order by p.payment_date desc, a.created_at desc, a.id
                    """).param("t", ctx.tenantId()).param("i", r.id()).query(PayRow.class).list().stream()
                    .map(p -> new PaymentLink(p.paymentId(), p.paymentDate(), Currencies.format(p.amount(), cur), p.method(), p.reference(), p.status())).toList();
        }
        return new Invoice(r.id(), r.number(), r.status(), displayStatus(r, today), overdue(r, today), days, r.clientId(), r.clientName(), r.projectId(), r.projectName(),
                r.invoiceDate(), r.dueDate(), cur, Currencies.format(r.subtotal(), cur), Currencies.format(r.discountTotal(), cur), Currencies.format(r.taxTotal(), cur),
                Currencies.format(r.total(), cur), Currencies.format(r.paid(), cur), Currencies.format(outstanding(r), cur), r.notes(), r.issuedAt(), r.cancelledAt(),
                r.cancelReason(), r.version(), lines, payments);
    }

    public Page<Invoice> list(BusinessContext ctx, String status, UUID clientId, UUID projectId, String from, String to, String dueFrom, String dueTo, String q,
            Integer limit, Integer offset) {
        ctx.requireAll(Resource.INVOICES, Action.READ);
        int lim = Page.limit(limit);
        int off = Page.offset(offset);
        StringBuilder where = new StringBuilder("i.tenant_id = :t");
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null) {
            if (!FILTERS.contains(st)) {
                throw Validate.invalid("status is not a valid choice");
            }
            where.append(switch (st) {
                case "DRAFT", "CANCELLED", "ISSUED" -> " and i.status = '" + st + "'";
                case "PAID" -> " and i.status = 'ISSUED' and i.total - coalesce(pd.paid, 0) <= 0";
                case "OVERDUE" -> " and i.status = 'ISSUED' and i.total - coalesce(pd.paid, 0) > 0 and i.due_date < :today";
                case "PARTIALLY_PAID" -> " and i.status = 'ISSUED' and coalesce(pd.paid, 0) > 0 and i.total - coalesce(pd.paid, 0) > 0 and i.due_date >= :today";
                default -> " and i.status = 'ISSUED' and coalesce(pd.paid, 0) = 0 and i.total > 0 and i.due_date >= :today";
            });
        }
        LocalDate f = Validate.date("from", from, false);
        LocalDate t = Validate.date("to", to, false);
        LocalDate df = Validate.date("due_from", dueFrom, false);
        LocalDate dt = Validate.date("due_to", dueTo, false);
        if (clientId != null) {
            where.append(" and i.client_id = :client");
        }
        if (projectId != null) {
            where.append(" and i.project_id = :project");
        }
        if (f != null) {
            where.append(" and i.invoice_date >= :from");
        }
        if (t != null) {
            where.append(" and i.invoice_date <= :to");
        }
        if (df != null) {
            where.append(" and i.due_date >= :dueFrom");
        }
        if (dt != null) {
            where.append(" and i.due_date <= :dueTo");
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append(" and (lower(coalesce(i.number, '')) like :q escape '\\' or lower(c.name) like :q escape '\\')");
        }
        var count = jdbc.sql("select count(*) from (select 1 " + SELECT.substring(SELECT.indexOf("from invoices i")) + " where " + where + ") x").param("t", ctx.tenantId());
        var query = jdbc.sql(SELECT + " where " + where + " order by i.invoice_date desc, i.created_at desc, i.id limit :lim offset :off")
                .param("t", ctx.tenantId()).param("lim", lim).param("off", off);
        if (st != null && Set.of("PAID", "OVERDUE", "PARTIALLY_PAID", "UNPAID").contains(st)) {
            count = count.param("today", ctx.today());
            query = query.param("today", ctx.today());
        }
        if (clientId != null) {
            count = count.param("client", clientId);
            query = query.param("client", clientId);
        }
        if (projectId != null) {
            count = count.param("project", projectId);
            query = query.param("project", projectId);
        }
        if (f != null) {
            count = count.param("from", f);
            query = query.param("from", f);
        }
        if (t != null) {
            count = count.param("to", t);
            query = query.param("to", t);
        }
        if (df != null) {
            count = count.param("dueFrom", df);
            query = query.param("dueFrom", df);
        }
        if (dt != null) {
            count = count.param("dueTo", dt);
            query = query.param("dueTo", dt);
        }
        if (search) {
            count = count.param("q", BizSql.like(q));
            query = query.param("q", BizSql.like(q));
        }
        return new Page<>(query.query(Row.class).list().stream().map(r -> view(ctx, r, false)).toList(), count.query(Long.class).single(), lim, off);
    }

    public Invoice get(BusinessContext ctx, UUID id) {
        ctx.requireAll(Resource.INVOICES, Action.READ);
        return view(ctx, row(ctx, id, false), true);
    }

    // ---- drafting ----

    private Computed compute(List<LineRequest> lines, String currency) {
        if (lines == null || lines.isEmpty()) {
            throw Validate.invalid("An invoice needs at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw Validate.invalid("An invoice can have at most " + MAX_LINES + " lines");
        }
        int exp = Currencies.exponentOrThrow(currency);
        List<LineRow> rows = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int pos = 1;
        for (LineRequest l : lines) {
            if (l == null) {
                throw Validate.invalid("line " + pos + " is empty");
            }
            String where = "line " + pos;
            String description = Validate.text(where + " description", l.description(), 1, 300, true);
            if (l.quantity() == null || !QUANTITY.matcher(l.quantity().trim()).matches() || new BigDecimal(l.quantity().trim()).signum() <= 0) {
                throw Validate.invalid(where + " quantity must be a positive number with at most 4 decimals");
            }
            BigDecimal quantity = new BigDecimal(l.quantity().trim());
            BigDecimal unit = Amounts.parse(where + " unit_price", l.unitPrice(), currency, Amounts.Sign.ANY);
            if (unit.signum() < 0) {
                throw Validate.invalid(where + " unit_price cannot be negative");
            }
            BigDecimal discount = l.discount() == null || l.discount().isBlank() ? BigDecimal.ZERO.setScale(exp) : Amounts.parse(where + " discount", l.discount(), currency, Amounts.Sign.ANY);
            if (discount.signum() < 0) {
                throw Validate.invalid(where + " discount cannot be negative");
            }
            BigDecimal pct = BigDecimal.ZERO;
            if (l.taxPercent() != null && !l.taxPercent().isBlank()) {
                if (!PERCENT.matcher(l.taxPercent().trim()).matches() || new BigDecimal(l.taxPercent().trim()).compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw Validate.invalid(where + " tax_percent must be between 0 and 100 with at most 2 decimals");
                }
                pct = new BigDecimal(l.taxPercent().trim());
            }
            InvoiceMath.Line calc = InvoiceMath.line(quantity, unit, discount, pct, exp);
            if (discount.compareTo(calc.gross()) > 0) {
                throw Validate.invalid(where + " discount cannot exceed the line amount");
            }
            if (calc.total().compareTo(Currencies.MAX_AMOUNT) > 0) {
                throw Validate.invalid(where + " amount is too large");
            }
            rows.add(new LineRow(pos++, description, quantity, unit, calc.gross(), discount, calc.net(), pct.setScale(2), calc.tax(), calc.total()));
            subtotal = subtotal.add(calc.gross());
            discountTotal = discountTotal.add(discount);
            tax = tax.add(calc.tax());
            total = total.add(calc.total());
        }
        if (total.compareTo(Currencies.MAX_AMOUNT) > 0) {
            throw Validate.invalid("The invoice total is too large");
        }
        return new Computed(rows, subtotal, discountTotal, tax, total);
    }

    private void insertLines(BusinessContext ctx, UUID invoice, List<LineRow> rows) {
        for (LineRow l : rows) {
            jdbc.sql("""
                    insert into invoice_lines (id, tenant_id, invoice_id, position, description, quantity, unit_price, gross_amount, discount, net_amount, tax_percent, tax_amount, line_total)
                    values (:id, :t, :i, :pos, :d, :q, :u, :g, :disc, :n, :pct, :tax, :tot)
                    """).param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("i", invoice).param("pos", l.position()).param("d", l.description())
                    .param("q", l.quantity()).param("u", l.unitPrice()).param("g", l.grossAmount()).param("disc", l.discount()).param("n", l.netAmount())
                    .param("pct", l.taxPercent()).param("tax", l.taxAmount()).param("tot", l.lineTotal()).update();
        }
    }

    private void checkProject(BusinessContext ctx, UUID projectId, UUID clientId, String currency) {
        var p = jdbc.sql("select status, currency, client_id, archived_at is not null from projects where tenant_id = :t and id = :id").param("t", ctx.tenantId())
                .param("id", projectId).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2).trim(), rs.getObject(3, UUID.class), rs.getBoolean(4)}).optional();
        if (p.isEmpty()) {
            throw Validate.invalid("project_id does not refer to an active project");
        }
        Object[] v = p.get();
        if ((Boolean) v[3] || !ProjectService.OPEN.contains((String) v[0])) {
            throw new ApiException(ErrorCode.CONFLICT, "The project is closed or archived and accepts no new invoices");
        }
        if (!currency.equals(v[1])) {
            throw Validate.invalid("The invoice currency must match the project currency (" + v[1] + "); nothing is converted");
        }
        if (v[2] != null && !v[2].equals(clientId)) {
            throw Validate.invalid("The project belongs to a different client");
        }
    }

    private record ClientInfo(Integer terms, boolean archived) {
    }

    private ClientInfo client(BusinessContext ctx, UUID id) {
        return jdbc.sql("select payment_terms_days as terms, archived_at is not null as archived from clients where tenant_id = :t and id = :id")
                .param("t", ctx.tenantId()).param("id", id).query(ClientInfo.class).optional()
                .orElseThrow(() -> Validate.invalid("client_id does not refer to an active client"));
    }

    private LocalDate dueDate(BusinessContext ctx, LocalDate invoiceDate, String dueRaw, Integer termsRaw, Integer clientTerms) {
        LocalDate explicit = Validate.date("due_date", dueRaw, false);
        if (explicit != null) {
            if (explicit.isBefore(invoiceDate)) {
                throw Validate.invalid("due_date cannot be before invoice_date");
            }
            return explicit;
        }
        int terms = termsRaw != null ? termsRaw : clientTerms != null ? clientTerms : settings.defaults(ctx).defaultTermsDays();
        if (terms < 0 || terms > 365) {
            throw Validate.invalid("terms_days must be between 0 and 365");
        }
        return invoiceDate.plusDays(terms);
    }

    @Transactional
    public Invoice create(BusinessContext ctx, CreateRequest req) {
        ctx.requireAll(Resource.INVOICES, Action.CREATE);
        UUID clientId = Validate.uuid("client_id", req.clientId(), true);
        ClientInfo client = client(ctx, clientId);
        if (client.archived()) {
            throw Validate.invalid("client_id does not refer to an active client");
        }
        String currency = req.currency() == null ? settings.defaults(ctx).baseCurrency().trim() : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        UUID project = Validate.uuid("project_id", req.projectId(), false);
        if (project != null) {
            checkProject(ctx, project, clientId, currency);
        }
        LocalDate invoiceDate = req.invoiceDate() == null ? ctx.today() : Validate.date("invoice_date", req.invoiceDate(), true);
        LocalDate due = dueDate(ctx, invoiceDate, req.dueDate(), req.termsDays(), client.terms());
        Computed c = compute(req.lines(), currency);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into invoices (id, tenant_id, client_id, project_id, status, invoice_date, due_date, currency, subtotal, discount_total, tax_total, total, notes, created_by, created_at, updated_at)
                values (:id, :t, :c, :p, 'DRAFT', :d, :due, :cur, :sub, :disc, :tax, :tot, :notes, :u, :now, :now)
                """).param("id", id).param("t", ctx.tenantId()).param("c", clientId).param("p", project).param("d", invoiceDate).param("due", due).param("cur", currency)
                .param("sub", c.subtotal()).param("disc", c.discount()).param("tax", c.tax()).param("tot", c.total()).param("notes", Validate.text("notes", req.notes(), 1, 1000, false))
                .param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
        insertLines(ctx, id, c.rows());
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "INVOICE_DRAFT_CREATED", "INVOICE", id, Currencies.format(c.total(), currency) + " " + currency);
        return view(ctx, row(ctx, id, false), true);
    }

    @Transactional
    public Invoice update(BusinessContext ctx, UUID id, UpdateRequest req) {
        ctx.requireAll(Resource.INVOICES, Action.UPDATE);
        Row r = row(ctx, id, true);
        if (!"DRAFT".equals(r.status())) {
            throw new ApiException(ErrorCode.INVOICE_IMMUTABLE, "Only a draft invoice can be edited; issued invoices are corrected by cancelling and re-issuing");
        }
        if (Validate.version(req.version()) != r.version()) {
            throw new ApiException(ErrorCode.CONFLICT, "This invoice was changed elsewhere. Reload and try again.");
        }
        String currency = r.currency().trim();
        UUID project = Boolean.TRUE.equals(req.clearProject()) ? null : req.projectId() == null ? r.projectId() : Validate.uuid("project_id", req.projectId(), true);
        if (project != null && !project.equals(r.projectId())) {
            checkProject(ctx, project, r.clientId(), currency);
        }
        LocalDate invoiceDate = req.invoiceDate() == null ? r.invoiceDate() : Validate.date("invoice_date", req.invoiceDate(), true);
        LocalDate due = r.dueDate();
        if (req.dueDate() != null || req.termsDays() != null || !invoiceDate.equals(r.invoiceDate())) {
            // Moving the invoice date without naming a due date keeps the same number of days of terms.
            String explicit = req.dueDate() != null ? req.dueDate() : null;
            Integer terms = req.termsDays() != null ? req.termsDays() : (int) ChronoUnit.DAYS.between(r.invoiceDate(), r.dueDate());
            due = dueDate(ctx, invoiceDate, explicit, terms, null);
        }
        BigDecimal subtotal = r.subtotal();
        BigDecimal disc = r.discountTotal();
        BigDecimal tax = r.taxTotal();
        BigDecimal total = r.total();
        if (req.lines() != null) {
            Computed c = compute(req.lines(), currency);
            jdbc.sql("delete from invoice_lines where tenant_id = :t and invoice_id = :i").param("t", ctx.tenantId()).param("i", id).update();
            insertLines(ctx, id, c.rows());
            subtotal = c.subtotal();
            disc = c.discount();
            tax = c.tax();
            total = c.total();
        }
        jdbc.sql("""
                update invoices set project_id = :p, invoice_date = :d, due_date = :due, subtotal = :sub, discount_total = :disc, tax_total = :tax, total = :tot,
                       notes = :notes, version = version + 1, updated_at = :now where tenant_id = :t and id = :id
                """).param("p", project).param("d", invoiceDate).param("due", due).param("sub", subtotal).param("disc", disc).param("tax", tax).param("tot", total)
                .param("notes", req.notes() == null ? r.notes() : Validate.text("notes", req.notes(), 1, 1000, false)).param("now", BizSql.now(ctx))
                .param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "INVOICE_DRAFT_UPDATED", "INVOICE", id,
                "total " + Currencies.format(r.total(), currency) + "->" + Currencies.format(total, currency) + (req.lines() != null ? " lines replaced" : ""));
        return view(ctx, row(ctx, id, false), true);
    }

    // ---- lifecycle ----

    private record Numbering(String prefix, int padding, long used) {
    }

    @Transactional
    public Invoice issue(BusinessContext ctx, UUID id, IssueRequest req) {
        ctx.requireAll(Resource.INVOICES, Action.ISSUE);
        Row r = row(ctx, id, true);
        if (!"DRAFT".equals(r.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only a draft invoice can be issued (this one is " + r.status().toLowerCase(Locale.ROOT) + ")");
        }
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw Validate.invalid("confirm must be true to issue an invoice");
        }
        String currency = r.currency().trim();
        BigDecimal expected = Amounts.parse("expected_total", req.expectedTotal(), currency, Amounts.Sign.ANY);
        if (expected.compareTo(r.total()) != 0) {
            throw new ApiException(ErrorCode.TOTAL_MISMATCH, "The invoice total is " + Currencies.format(r.total(), currency) + " " + currency + ", not " + req.expectedTotal());
        }
        if (r.total().signum() <= 0) {
            throw Validate.invalid("An invoice with a zero total cannot be issued");
        }
        if (client(ctx, r.clientId()).archived()) {
            throw new ApiException(ErrorCode.CONFLICT, "The client is archived; restore it before issuing");
        }
        if (r.projectId() != null) {
            checkProject(ctx, r.projectId(), r.clientId(), currency);
        }
        settings.defaults(ctx); // makes sure the numbering row exists
        // One atomic statement takes the next number; concurrent issues queue on this row, so numbers are unique and, because a
        // failed issue rolls this increment back with the rest of the transaction, never skipped.
        Numbering n = jdbc.sql("""
                update business_settings set invoice_next_number = invoice_next_number + 1, updated_at = :now where tenant_id = :t
                returning invoice_prefix as prefix, invoice_padding as padding, invoice_next_number - 1 as used
                """).param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).query(Numbering.class).single();
        String number = BusinessSettingsService.formatNumber(n.prefix(), n.padding(), n.used());
        try {
            jdbc.sql("""
                    update invoices set status = 'ISSUED', number = :n, issued_at = :now, issued_by = :u, version = version + 1, updated_at = :now
                    where tenant_id = :t and id = :id
                    """).param("n", number).param("now", BizSql.now(ctx)).param("u", ctx.userId()).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "That invoice number is already in use; adjust the next number in settings");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "INVOICE_ISSUED", "INVOICE", id, number + " " + Currencies.format(r.total(), currency) + " " + currency);
        return view(ctx, row(ctx, id, false), true);
    }

    @Transactional
    public Invoice cancel(BusinessContext ctx, UUID id, CancelRequest req) {
        ctx.requireAll(Resource.INVOICES, Action.ARCHIVE);
        Row r = row(ctx, id, true);
        String reason = Validate.text("reason", req.reason(), 1, 200, true);
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw Validate.invalid("confirm must be true to cancel an invoice");
        }
        if ("CANCELLED".equals(r.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The invoice is already cancelled");
        }
        if ("ISSUED".equals(r.status())) {
            long allocations = jdbc.sql("""
                    select count(*) from payment_allocations a join payments p on p.tenant_id = a.tenant_id and p.id = a.payment_id
                    where a.tenant_id = :t and a.invoice_id = :i and p.status = 'RECORDED'
                    """).param("t", ctx.tenantId()).param("i", id).query(Long.class).single();
            if (allocations > 0) {
                throw new ApiException(ErrorCode.ALLOCATION_EXISTS, "Void the payments applied to this invoice before cancelling it");
            }
        }
        jdbc.sql("update invoices set status = 'CANCELLED', cancelled_at = :now, cancel_reason = :r, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("now", BizSql.now(ctx)).param("r", reason).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "INVOICE_CANCELLED", "INVOICE", id, (r.number() == null ? "draft" : r.number()) + " was " + r.status());
        return view(ctx, row(ctx, id, false), true);
    }
}
