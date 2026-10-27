package com.finora.finance.assistant;

import com.finora.finance.insights.InsightsContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The few read-only queries the assistant needs on top of the reports (a spending breakdown by category and the individual expense records).
 * They use the same definitions as the reports (POSTED records only, refunds deducted, transfers excluded) and always filter by the workspace
 * id taken from the verified token. Nothing here writes.
 */
@Component
class AssistantQueries {

    record CategoryRow(String currency, String key, String label, BigDecimal amount, long records) {
    }

    record Rec(UUID id, LocalDate onDate, String currency, String category, String counterparty, BigDecimal amount) {
    }

    static final int MAX_RECORDS = 20000;

    private final JdbcClient jdbc;

    AssistantQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Net spending per category and currency: expenses minus refunds (a refund is counted in its own category) for personal, posted expenses for business. */
    List<CategoryRow> categoryTotals(InsightsContext ctx, LocalDate from, LocalDate to) {
        if (!ctx.business()) {
            return jdbc.sql("""
                    select t.currency, coalesce(c.id::text, 'none') as key, coalesce(c.name, 'Uncategorised') as label,
                           sum(case when t.kind = 'EXPENSE' then t.amount else -t.amount end) as amount, count(*) as records
                    from transactions t left join categories c on c.tenant_id = t.tenant_id and c.id = t.category_id
                    where t.tenant_id = :t and t.status = 'POSTED' and t.kind in ('EXPENSE', 'REFUND') and t.occurred_on between :f and :to
                    group by t.currency, c.id, c.name order by t.currency, 4 desc, 3
                    """).param("t", ctx.tenantId()).param("f", from).param("to", to).query(CategoryRow.class).list();
        }
        return jdbc.sql("""
                select e.currency, coalesce(c.id::text, 'none') as key, coalesce(c.name, 'Uncategorised') as label, sum(e.amount) as amount, count(*) as records
                from business_expenses e left join expense_categories c on c.tenant_id = e.tenant_id and c.id = e.category_id
                where e.tenant_id = :t and e.status = 'POSTED' and e.expense_date between :f and :to
                group by e.currency, c.id, c.name order by e.currency, 4 desc, 3
                """).param("t", ctx.tenantId()).param("f", from).param("to", to).query(CategoryRow.class).list();
    }

    /** Individual expense records (personal expenses, or posted business expenses), newest first, bounded. */
    List<Rec> expenseRecords(InsightsContext ctx, LocalDate from, LocalDate to) {
        if (!ctx.business()) {
            return jdbc.sql("""
                    select t.id, t.occurred_on as on_date, t.currency, coalesce(c.name, 'Uncategorised') as category, t.payee as counterparty, t.amount
                    from transactions t left join categories c on c.tenant_id = t.tenant_id and c.id = t.category_id
                    where t.tenant_id = :t and t.status = 'POSTED' and t.kind = 'EXPENSE' and t.occurred_on between :f and :to
                    order by t.occurred_on desc, t.id limit %d
                    """.formatted(MAX_RECORDS)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(Rec.class).list();
        }
        return jdbc.sql("""
                select e.id, e.expense_date as on_date, e.currency, coalesce(c.name, 'Uncategorised') as category, v.name as counterparty, e.amount
                from business_expenses e left join expense_categories c on c.tenant_id = e.tenant_id and c.id = e.category_id
                left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id
                where e.tenant_id = :t and e.status = 'POSTED' and e.expense_date between :f and :to
                order by e.expense_date desc, e.id limit %d
                """.formatted(MAX_RECORDS)).param("t", ctx.tenantId()).param("f", from).param("to", to).query(Rec.class).list();
    }
}
