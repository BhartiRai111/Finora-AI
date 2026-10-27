package com.finora.finance.accounts;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Amounts;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal financial accounts. A balance is never stored: it is the opening balance plus the exact sum of POSTED
 * transactions up to a date, computed with BigDecimal. Liabilities (credit cards, loans) are shown as the amount owed.
 */
@Service
public class AccountService {

    public static final Set<String> LIABILITY_TYPES = Set.of("CREDIT_CARD", "LOAN", "OTHER_LIABILITY");
    private static final Set<String> TYPES = Set.of("CASH", "BANK", "WALLET", "INVESTMENT", "OTHER_ASSET", "CREDIT_CARD", "LOAN", "OTHER_LIABILITY");

    public record Row(UUID id, String name, String type, String currency, BigDecimal openingBalance, LocalDate openingDate,
            OffsetDateTime archivedAt, int version) {
        public boolean liability() {
            return LIABILITY_TYPES.contains(type);
        }
    }

    public record View(UUID id, String name, String type, String currency, String openingBalance, LocalDate openingDate,
            String balance, boolean liability, boolean archived, int version) {
    }

    public record CreateRequest(String name, String type, String currency, String openingBalance, String openingDate) {
    }

    public record AdjustRequest(String newBalance, String asOf, String note) {
    }

    private record Legs(UUID acct, BigDecimal inflow, BigDecimal outflow, BigDecimal tout, BigDecimal tin, BigDecimal ainc,
            BigDecimal adec) {
    }

    static final String COLS = "id, name, type, currency, opening_balance, opening_date, archived_at, version";

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public AccountService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- balances ----

    /** Balance of every account that existed on {@code asOf}, by exact decimal arithmetic. */
    public Map<UUID, BigDecimal> balances(UUID tenant, LocalDate asOf, Map<UUID, Row> accounts) {
        List<Legs> legs = jdbc.sql("""
                select acct,
                       coalesce(sum(a) filter (where leg = 'IN'), 0)   as inflow,
                       coalesce(sum(a) filter (where leg = 'OUT'), 0)  as outflow,
                       coalesce(sum(a) filter (where leg = 'TOUT'), 0) as tout,
                       coalesce(sum(a) filter (where leg = 'TIN'), 0)  as tin,
                       coalesce(sum(a) filter (where leg = 'AINC'), 0) as ainc,
                       coalesce(sum(a) filter (where leg = 'ADEC'), 0) as adec
                from (
                    select account_id as acct,
                           case kind when 'INCOME' then 'IN' when 'REFUND' then 'IN' when 'EXPENSE' then 'OUT'
                                     when 'TRANSFER' then 'TOUT'
                                     else case direction when 'INCREASE' then 'AINC' else 'ADEC' end end as leg,
                           amount as a
                    from transactions where tenant_id = :t and status = 'POSTED' and occurred_on <= :d
                    union all
                    select to_account_id, 'TIN', to_amount
                    from transactions where tenant_id = :t and status = 'POSTED' and kind = 'TRANSFER' and occurred_on <= :d
                ) l group by acct
                """).param("t", tenant).param("d", asOf).query(Legs.class).list();
        Map<UUID, Legs> byAccount = new HashMap<>();
        legs.forEach(l -> byAccount.put(l.acct(), l));
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (Row a : accounts.values()) {
            if (a.openingDate().isAfter(asOf)) {
                continue;
            }
            Legs l = byAccount.get(a.id());
            BigDecimal b = a.openingBalance();
            if (l != null) {
                BigDecimal cashIn = l.inflow().add(l.tin());
                BigDecimal cashOut = l.outflow().add(l.tout());
                // Asset: money in raises the balance. Liability: the balance is the amount owed, so money in lowers it.
                b = a.liability() ? b.subtract(cashIn).add(cashOut) : b.add(cashIn).subtract(cashOut);
                b = b.add(l.ainc()).subtract(l.adec());
            }
            result.put(a.id(), b);
        }
        return result;
    }

    public Map<UUID, Row> rows(UUID tenant, boolean includeArchived) {
        Map<UUID, Row> map = new java.util.LinkedHashMap<>();
        jdbc.sql("select " + COLS + " from accounts where tenant_id = :t " + (includeArchived ? "" : "and archived_at is null ")
                + "order by lower(name), id").param("t", tenant).query(Row.class).list().forEach(r -> map.put(r.id(), r));
        return map;
    }

    public Row require(UUID tenant, UUID id) {
        return jdbc.sql("select " + COLS + " from accounts where tenant_id = :t and id = :id").param("t", tenant).param("id", id)
                .query(Row.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    /** For writes that reference an account from a request body: missing and foreign accounts look identical. */
    public Row requireUsable(UUID tenant, UUID id, String field) {
        if (id == null) {
            throw Validate.invalid(field + " is required");
        }
        Row r = jdbc.sql("select " + COLS + " from accounts where tenant_id = :t and id = :id").param("t", tenant).param("id", id)
                .query(Row.class).optional().orElseThrow(() -> Validate.invalid(field + " does not exist"));
        if (r.archivedAt() != null) {
            throw Validate.invalid(field + " is archived");
        }
        return r;
    }

    // ---- operations ----

    public List<View> list(FinanceContext ctx, boolean includeArchived) {
        ctx.require(Action.READ);
        Map<UUID, Row> rows = rows(ctx.tenantId(), includeArchived);
        Map<UUID, BigDecimal> balances = balances(ctx.tenantId(), ctx.today(), rows);
        return rows.values().stream().map(r -> view(r, balances.get(r.id()))).toList();
    }

    public View get(FinanceContext ctx, UUID id) {
        ctx.require(Action.READ);
        Row r = require(ctx.tenantId(), id);
        return view(r, balances(ctx.tenantId(), ctx.today(), Map.of(r.id(), r)).get(r.id()));
    }

    static View view(Row r, BigDecimal balance) {
        return new View(r.id(), r.name(), r.type(), r.currency(), Currencies.format(r.openingBalance(), r.currency()),
                r.openingDate(), balance == null ? null : Currencies.format(balance, r.currency()), r.liability(),
                r.archivedAt() != null, r.version());
    }

    @Transactional
    public View create(FinanceContext ctx, CreateRequest req) {
        ctx.require(Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 100, true);
        String type = Validate.choice("type", req.type(), AccountType.class).name();
        String currency = req.currency() == null ? null : req.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!Currencies.supported(currency)) {
            throw Validate.invalid("currency is not supported");
        }
        BigDecimal opening = Amounts.parse("opening_balance", req.openingBalance() == null ? "0" : req.openingBalance(), currency, Amounts.Sign.ANY);
        if (LIABILITY_TYPES.contains(type) && opening.signum() < 0) {
            throw Validate.invalid("opening_balance for a liability is the amount owed and cannot be negative");
        }
        LocalDate openingDate = Validate.date("opening_date", req.openingDate(), false);
        if (openingDate == null) {
            openingDate = ctx.today();
        }
        if (openingDate.isAfter(ctx.today().plusDays(366))) {
            throw Validate.invalid("opening_date is too far in the future");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
        try {
            jdbc.sql("""
                    insert into accounts (id, tenant_id, name, type, currency, opening_balance, opening_date, created_at, updated_at)
                    values (:id, :t, :n, :ty, :c, :ob, :od, :now, :now)
                    """)
                    .param("id", id).param("t", ctx.tenantId()).param("n", name).param("ty", type).param("c", currency)
                    .param("ob", opening).param("od", openingDate).param("now", now).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "You already have an account with that name");
        }
        audit.record(ctx, "ACCOUNT_CREATED", "ACCOUNT", id, type + " " + currency);
        return get(ctx, id);
    }

    enum AccountType { CASH, BANK, WALLET, INVESTMENT, OTHER_ASSET, CREDIT_CARD, LOAN, OTHER_LIABILITY }

    @Transactional
    public View rename(FinanceContext ctx, UUID id, String newName, Integer version) {
        ctx.require(Action.UPDATE);
        String name = Validate.text("name", newName, 1, 100, true);
        int v = Validate.version(version);
        Row before = require(ctx.tenantId(), id);
        int updated;
        try {
            updated = jdbc.sql("update accounts set name = :n, version = version + 1, updated_at = :now where tenant_id = :t and id = :id and version = :v")
                    .param("n", name).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC))
                    .param("t", ctx.tenantId()).param("id", id).param("v", v).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "You already have an account with that name");
        }
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This account was changed elsewhere; reload and try again");
        }
        audit.record(ctx, "ACCOUNT_RENAMED", "ACCOUNT", id, "name: " + before.name() + " -> " + name);
        return get(ctx, id);
    }

    @Transactional
    public View setArchived(FinanceContext ctx, UUID id, boolean archived) {
        ctx.require(Action.ARCHIVE);
        require(ctx.tenantId(), id);
        OffsetDateTime now = OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
        try {
            jdbc.sql("update accounts set archived_at = :a, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                    .param("a", archived ? now : null).param("now", now).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "Another active account already uses this name");
        }
        audit.record(ctx, archived ? "ACCOUNT_ARCHIVED" : "ACCOUNT_RESTORED", "ACCOUNT", id, null);
        return get(ctx, id);
    }

    /**
     * Sets the account's balance on a date to a value the user states (for example from a statement or a manual valuation)
     * by recording an explicit, labelled ADJUSTMENT transaction for the exact difference. Adjustments are excluded from
     * income and expense totals and appear in the history; nothing is changed silently.
     */
    @Transactional
    public Map<String, Object> adjust(FinanceContext ctx, UUID id, AdjustRequest req) {
        ctx.require(Action.CREATE);
        jdbc.sql("select id from accounts where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        Row account = requireUsable(ctx.tenantId(), id, "account");
        BigDecimal target = Amounts.parse("new_balance", req.newBalance(), account.currency(), Amounts.Sign.ANY);
        if (account.liability() && target.signum() < 0) {
            throw Validate.invalid("new_balance for a liability is the amount owed and cannot be negative");
        }
        LocalDate asOf = Validate.date("as_of", req.asOf(), false);
        if (asOf == null) {
            asOf = ctx.today();
        }
        if (asOf.isBefore(account.openingDate()) || asOf.isAfter(ctx.today())) {
            throw Validate.invalid("as_of must be between the account's opening date and today");
        }
        String note = Validate.text("note", req.note(), 1, 1000, false);
        BigDecimal current = balances(ctx.tenantId(), asOf, Map.of(id, account)).get(id);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            throw Validate.invalid("The balance already equals new_balance on that date; no adjustment needed");
        }
        UUID txId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
        jdbc.sql("""
                insert into transactions (id, tenant_id, kind, status, account_id, amount, currency, direction, occurred_on, note,
                                          created_by, created_at, updated_at)
                values (:id, :t, 'ADJUSTMENT', 'POSTED', :a, :amt, :c, :dir, :d, :note, :u, :now, :now)
                """)
                .param("id", txId).param("t", ctx.tenantId()).param("a", id).param("amt", delta.abs()).param("c", account.currency())
                .param("dir", delta.signum() > 0 ? "INCREASE" : "DECREASE").param("d", asOf)
                .param("note", note == null ? "Manual balance adjustment" : note).param("u", ctx.userId()).param("now", now).update();
        audit.record(ctx, "ACCOUNT_ADJUSTED", "ACCOUNT", id, "balance " + Currencies.format(current, account.currency()) + " -> "
                + Currencies.format(target, account.currency()) + " as of " + asOf + " (manual)");
        return Map.of("transaction_id", txId, "difference", Currencies.format(delta, account.currency()),
                "balance", Currencies.format(target, account.currency()), "as_of", asOf.toString(), "source", "manual");
    }
}
