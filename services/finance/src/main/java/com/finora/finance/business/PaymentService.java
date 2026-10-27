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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments received from clients and their allocation to invoices. A payment may be larger than what is allocated: the
 * rest stays on the payment as an explicit unallocated balance (an advance or overpayment) and can be allocated later; it
 * never silently reduces another invoice. An allocation can never exceed the invoice's outstanding amount or the
 * payment's unallocated amount, and the check runs under row locks on the invoices, so concurrent payments cannot
 * overpay an invoice. Voiding a payment reverses all its allocations at once (the outstanding balances come back).
 * Same client + same reference cannot be recorded twice while the first payment stands.
 */
@Service
public class PaymentService {

    static final Set<String> METHODS = Set.of("BANK_TRANSFER", "UPI", "CASH", "CHEQUE", "CARD", "OTHER");

    public record AllocationRequest(String invoiceId, String amount) {
    }

    public record Request(String clientId, String paymentDate, String amount, String currency, String method, String reference, String note,
            List<AllocationRequest> allocations, Boolean autoAllocate) {
    }

    public record AllocateRequest(List<AllocationRequest> allocations) {
    }

    public record VoidRequest(String reason) {
    }

    public record Allocation(UUID invoiceId, String invoiceNumber, String amount) {
    }

    public record Payment(UUID id, UUID clientId, String clientName, LocalDate paymentDate, String amount, String currency, String method, String reference,
            String note, String status, String voidReason, String allocated, String unallocated, int version, List<Allocation> allocations) {
    }

    private record Row(UUID id, UUID clientId, String clientName, LocalDate paymentDate, BigDecimal amount, String currency, String method, String reference,
            String note, String status, String voidReason, BigDecimal allocated, int version) {
    }

    private record AllocRow(UUID invoiceId, String number, BigDecimal amount) {
    }

    private static final String SELECT = """
            select p.id, p.client_id, c.name as client_name, p.payment_date, p.amount, p.currency, p.method, p.reference, p.note, p.status, p.void_reason,
                   coalesce((select sum(a.amount) from payment_allocations a where a.tenant_id = p.tenant_id and a.payment_id = p.id), 0) as allocated, p.version
            from payments p join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id
            """;

    private final JdbcClient jdbc;
    private final FinanceAudit audit;
    private final InvoiceService invoices;

    public PaymentService(JdbcClient jdbc, FinanceAudit audit, InvoiceService invoices) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.invoices = invoices;
    }

    private Row row(BusinessContext ctx, UUID id, boolean lock) {
        if (lock) {
            jdbc.sql("select 1 from payments where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", id).query(Integer.class).optional()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        }
        return jdbc.sql(SELECT + " where p.tenant_id = :t and p.id = :id").param("t", ctx.tenantId()).param("id", id).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private Payment view(BusinessContext ctx, Row r, boolean detail) {
        String cur = r.currency().trim();
        boolean standing = "RECORDED".equals(r.status());
        BigDecimal allocated = standing ? r.allocated() : BigDecimal.ZERO;
        BigDecimal unallocated = standing ? r.amount().subtract(r.allocated()) : BigDecimal.ZERO;
        List<Allocation> allocations = detail ? jdbc.sql("""
                select a.invoice_id, i.number, a.amount from payment_allocations a join invoices i on i.tenant_id = a.tenant_id and i.id = a.invoice_id
                where a.tenant_id = :t and a.payment_id = :p order by i.due_date, i.number, a.id
                """).param("t", ctx.tenantId()).param("p", r.id()).query(AllocRow.class).list().stream()
                .map(a -> new Allocation(a.invoiceId(), a.number(), Currencies.format(a.amount(), cur))).toList() : null;
        return new Payment(r.id(), r.clientId(), r.clientName(), r.paymentDate(), Currencies.format(r.amount(), cur), cur, r.method(), r.reference(), r.note(), r.status(),
                r.voidReason(), Currencies.format(allocated, cur), Currencies.format(unallocated, cur), r.version(), allocations);
    }

    public Page<Payment> list(BusinessContext ctx, UUID clientId, String from, String to, String method, String q, boolean includeVoided, Integer limit, Integer offset) {
        ctx.requireAll(Resource.PAYMENTS, Action.READ);
        int lim = Page.limit(limit);
        int off = Page.offset(offset);
        LocalDate f = Validate.date("from", from, false);
        LocalDate t = Validate.date("to", to, false);
        String m = method == null || method.isBlank() ? null : method.trim().toUpperCase(Locale.ROOT);
        if (m != null && !METHODS.contains(m)) {
            throw Validate.invalid("method is not a valid choice");
        }
        StringBuilder where = new StringBuilder("p.tenant_id = :t");
        if (!includeVoided) {
            where.append(" and p.status = 'RECORDED'");
        }
        if (clientId != null) {
            where.append(" and p.client_id = :client");
        }
        if (f != null) {
            where.append(" and p.payment_date >= :from");
        }
        if (t != null) {
            where.append(" and p.payment_date <= :to");
        }
        if (m != null) {
            where.append(" and p.method = :method");
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append(" and (lower(coalesce(p.reference, '')) like :q escape '\\' or lower(c.name) like :q escape '\\')");
        }
        var count = jdbc.sql("select count(*) from payments p join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id where " + where).param("t", ctx.tenantId());
        var query = jdbc.sql(SELECT + " where " + where + " order by p.payment_date desc, p.created_at desc, p.id limit :lim offset :off")
                .param("t", ctx.tenantId()).param("lim", lim).param("off", off);
        if (clientId != null) {
            count = count.param("client", clientId);
            query = query.param("client", clientId);
        }
        if (f != null) {
            count = count.param("from", f);
            query = query.param("from", f);
        }
        if (t != null) {
            count = count.param("to", t);
            query = query.param("to", t);
        }
        if (m != null) {
            count = count.param("method", m);
            query = query.param("method", m);
        }
        if (search) {
            count = count.param("q", BizSql.like(q));
            query = query.param("q", BizSql.like(q));
        }
        return new Page<>(query.query(Row.class).list().stream().map(r -> view(ctx, r, false)).toList(), count.query(Long.class).single(), lim, off);
    }

    public Payment get(BusinessContext ctx, UUID id) {
        ctx.requireAll(Resource.PAYMENTS, Action.READ);
        return view(ctx, row(ctx, id, false), true);
    }

    @Transactional
    public Payment record(BusinessContext ctx, Request req) {
        ctx.requireAll(Resource.PAYMENTS, Action.CREATE);
        UUID clientId = Validate.uuid("client_id", req.clientId(), true);
        boolean clientOk = jdbc.sql("select count(*) from clients where tenant_id = :t and id = :id and archived_at is null").param("t", ctx.tenantId()).param("id", clientId)
                .query(Long.class).single() > 0;
        if (!clientOk) {
            throw Validate.invalid("client_id does not refer to an active client");
        }
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal amount = Amounts.parse("amount", req.amount(), currency, Amounts.Sign.POSITIVE);
        LocalDate date = Validate.date("payment_date", req.paymentDate(), true);
        if (date.isAfter(ctx.today())) {
            throw Validate.invalid("payment_date cannot be in the future");
        }
        String method = Validate.choice("method", req.method(), Method.class).name();
        boolean auto = Boolean.TRUE.equals(req.autoAllocate());
        if (auto && req.allocations() != null && !req.allocations().isEmpty()) {
            throw Validate.invalid("Use either allocations or auto_allocate, not both");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into payments (id, tenant_id, client_id, payment_date, amount, currency, method, reference, note, created_by, created_at, updated_at)
                    values (:id, :t, :c, :d, :a, :cur, :m, :ref, :note, :u, :now, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("c", clientId).param("d", date).param("a", amount).param("cur", currency).param("m", method)
                    .param("ref", Validate.text("reference", req.reference(), 1, 60, false)).param("note", Validate.text("note", req.note(), 1, 500, false))
                    .param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A payment from this client with this reference is already recorded");
        }
        BigDecimal applied;
        if (auto) {
            applied = autoAllocate(ctx, id, clientId, currency, amount);
        } else {
            applied = allocate(ctx, id, clientId, currency, amount, parseAllocations(req.allocations(), currency));
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "PAYMENT_RECORDED", "PAYMENT", id,
                Currencies.format(amount, currency) + " " + currency + " allocated " + Currencies.format(applied, currency));
        return view(ctx, row(ctx, id, false), true);
    }

    enum Method { BANK_TRANSFER, UPI, CASH, CHEQUE, CARD, OTHER }

    @Transactional
    public Payment allocateMore(BusinessContext ctx, UUID id, AllocateRequest req) {
        ctx.requireAll(Resource.PAYMENTS, Action.UPDATE);
        Row p = row(ctx, id, true);
        if (!"RECORDED".equals(p.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A voided payment cannot be allocated");
        }
        String currency = p.currency().trim();
        BigDecimal unallocated = p.amount().subtract(p.allocated());
        List<Alloc> allocs = parseAllocations(req.allocations(), currency);
        if (allocs.isEmpty()) {
            throw Validate.invalid("allocations are required");
        }
        BigDecimal applied = allocate(ctx, id, p.clientId(), currency, unallocated, allocs);
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "PAYMENT_ALLOCATED", "PAYMENT", id, Currencies.format(applied, currency) + " " + currency);
        return view(ctx, row(ctx, id, false), true);
    }

    @Transactional
    public Payment voidPayment(BusinessContext ctx, UUID id, VoidRequest req) {
        ctx.requireAll(Resource.PAYMENTS, Action.ARCHIVE);
        Row p = row(ctx, id, true);
        String reason = Validate.text("reason", req.reason(), 1, 200, true);
        if (!"RECORDED".equals(p.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The payment is already voided");
        }
        // Lock the affected invoices first (in id order) so a concurrent payment sees the restored balance consistently.
        jdbc.sql("select i.id from invoices i join payment_allocations a on a.tenant_id = i.tenant_id and a.invoice_id = i.id where a.tenant_id = :t and a.payment_id = :p order by i.id for update of i")
                .param("t", ctx.tenantId()).param("p", id).query(UUID.class).list();
        jdbc.sql("update payments set status = 'VOIDED', void_reason = :r, voided_at = :now, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("r", reason).param("now", BizSql.now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "PAYMENT_VOIDED", "PAYMENT", id, Currencies.format(p.amount(), p.currency().trim()) + " " + p.currency().trim());
        return view(ctx, row(ctx, id, false), true);
    }

    private record Alloc(UUID invoiceId, BigDecimal amount) {
    }

    private List<Alloc> parseAllocations(List<AllocationRequest> raw, String currency) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > 50) {
            throw Validate.invalid("at most 50 allocations per request");
        }
        List<Alloc> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (AllocationRequest a : raw) {
            if (a == null) {
                throw Validate.invalid("an allocation is empty");
            }
            UUID invoice = Validate.uuid("allocation invoice_id", a.invoiceId(), true);
            if (!seen.add(invoice)) {
                throw Validate.invalid("an invoice can appear only once per request");
            }
            out.add(new Alloc(invoice, Amounts.parse("allocation amount", a.amount(), currency, Amounts.Sign.POSITIVE)));
        }
        return out;
    }

    /** Applies explicit allocations. @param available what the payment can still allocate. @return the total applied. */
    private BigDecimal allocate(BusinessContext ctx, UUID payment, UUID clientId, String currency, BigDecimal available, List<Alloc> allocs) {
        if (allocs.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = allocs.stream().map(Alloc::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(available) > 0) {
            throw new ApiException(ErrorCode.ALLOCATION_EXCEEDS, "The allocations total " + Currencies.format(sum, currency) + " " + currency
                    + ", more than the " + Currencies.format(available, currency) + " " + currency + " available on the payment");
        }
        List<UUID> ids = allocs.stream().map(Alloc::invoiceId).sorted().toList();
        List<UUID> locked = jdbc.sql("select id from invoices where tenant_id = :t and id in (:ids) and client_id = :c order by id for update").param("t", ctx.tenantId())
                .param("ids", ids).param("c", clientId).query(UUID.class).list();
        if (locked.size() != ids.size()) {
            // Unknown, foreign-workspace and other-client invoices all answer the same.
            throw Validate.invalid("an allocation invoice_id does not refer to an invoice of this client");
        }
        for (Alloc a : allocs) {
            InvoiceService.Row inv = invoices.row(ctx, a.invoiceId(), false);
            apply(ctx, payment, inv, currency, a.amount());
        }
        return sum;
    }

    private void apply(BusinessContext ctx, UUID payment, InvoiceService.Row inv, String currency, BigDecimal amount) {
        if (!"ISSUED".equals(inv.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Payments can only be applied to issued invoices");
        }
        if (!inv.currency().trim().equals(currency)) {
            throw Validate.invalid("The payment currency must match the invoice currency; nothing is converted");
        }
        BigDecimal outstanding = InvoiceService.outstanding(inv);
        if (amount.compareTo(outstanding) > 0) {
            throw new ApiException(ErrorCode.ALLOCATION_EXCEEDS, "Invoice " + inv.number() + " has only " + Currencies.format(outstanding, currency) + " " + currency + " outstanding");
        }
        jdbc.sql("""
                insert into payment_allocations (id, tenant_id, payment_id, invoice_id, amount, created_by, created_at)
                values (:id, :t, :p, :i, :a, :u, :now)
                on conflict (payment_id, invoice_id) do update set amount = payment_allocations.amount + excluded.amount
                """).param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("p", payment).param("i", inv.id()).param("a", amount)
                .param("u", ctx.userId()).param("now", BizSql.now(ctx)).update();
    }

    /** Oldest due date first; whatever is left over stays unallocated on the payment. */
    private BigDecimal autoAllocate(BusinessContext ctx, UUID payment, UUID clientId, String currency, BigDecimal available) {
        List<UUID> locked = jdbc.sql("select id from invoices where tenant_id = :t and client_id = :c and status = 'ISSUED' and currency = :cur order by id for update")
                .param("t", ctx.tenantId()).param("c", clientId).param("cur", currency).query(UUID.class).list();
        Map<UUID, InvoiceService.Row> rows = new LinkedHashMap<>();
        for (UUID id : locked) {
            InvoiceService.Row r = invoices.row(ctx, id, false);
            if (InvoiceService.outstanding(r).signum() > 0) {
                rows.put(id, r);
            }
        }
        List<InvoiceService.Row> ordered = rows.values().stream()
                .sorted(java.util.Comparator.comparing(InvoiceService.Row::dueDate).thenComparing(InvoiceService.Row::invoiceDate).thenComparing(InvoiceService.Row::id)).toList();
        BigDecimal left = available;
        BigDecimal applied = BigDecimal.ZERO;
        for (InvoiceService.Row r : ordered) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal part = InvoiceService.outstanding(r).min(left);
            apply(ctx, payment, r, currency, part);
            left = left.subtract(part);
            applied = applied.add(part);
        }
        return applied;
    }
}
