package com.finora.finance.business;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** SQL fragments and small helpers shared by the business-finance services. */
public final class BizSql {

    private BizSql() {
    }

    /**
     * Amount applied to an invoice: allocations of payments that still stand. Voided payments never count, so cancelling
     * the payment restores the outstanding balance with no bookkeeping of its own.
     */
    public static final String PAID_LATERAL = """
            left join lateral (select coalesce(sum(a.amount), 0) as paid from payment_allocations a
                               join payments p on p.tenant_id = a.tenant_id and p.id = a.payment_id
                               where a.tenant_id = i.tenant_id and a.invoice_id = i.id and p.status = 'RECORDED') pd on true
            """;

    public static OffsetDateTime now(BusinessContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    /** Escapes LIKE wildcards so a search term is always matched literally. */
    public static String like(String q) {
        return "%" + q.trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
