package com.finora.finance.fx;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange rates of one workspace. No exchange-rate provider is approved, so the only source is a person entering a rate ("USER_SUPPLIED"),
 * and every use of a rate reports its value, date and source. A conversion looks for the latest ACTIVE rate for the exact pair dated on or
 * before the requested date (never a later one); a rate older than the workspace's stale threshold is reported as STALE and is not applied
 * unless the caller explicitly accepts stale rates. Inverse and cross rates are never derived. Nothing here changes a stored amount.
 */
@Service
public class RateService {

    public static final BigDecimal MAX_RATE = new BigDecimal("1000000000");
    public static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    public record RateView(UUID id, String baseCurrency, String quoteCurrency, String rate, LocalDate rateDate, String source, String sourceNote, String status,
            String voidReason, OffsetDateTime createdAt) {
    }

    public record CreateRequest(String baseCurrency, String quoteCurrency, String rate, String rateDate, String sourceNote) {
    }

    /** status: SAME (same currency, rate 1), APPLIED, STALE (found but older than the threshold), MISSING. */
    public record Lookup(String status, String baseCurrency, String quoteCurrency, LocalDate requestedDate, BigDecimal rate, LocalDate rateDate, Long ageDays, String source,
            UUID rateId, String message) {
        public boolean usable(boolean allowStale) {
            return "SAME".equals(status) || "APPLIED".equals(status) || (allowStale && "STALE".equals(status));
        }
    }

    private record Row(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate, LocalDate rateDate, String source, String sourceNote, String status, String voidReason,
            OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public RateService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static RateView view(Row r) {
        return new RateView(r.id(), r.baseCurrency().trim(), r.quoteCurrency().trim(), r.rate().stripTrailingZeros().toPlainString(), r.rateDate(), r.source(), r.sourceNote(), r.status(),
                r.voidReason(), r.createdAt());
    }

    private static String currency(String field, String raw) {
        if (raw == null) {
            throw Validate.invalid(field + " is required");
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (!Currencies.supported(c)) {
            throw Validate.invalid(field + " is not a supported currency");
        }
        return c;
    }

    public static BigDecimal parseRate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw Validate.invalid("rate is required");
        }
        BigDecimal r;
        try {
            r = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw Validate.invalid("rate must be a decimal number such as 83.2150");
        }
        if (r.signum() <= 0) {
            throw Validate.invalid("rate must be greater than zero");
        }
        if (r.compareTo(MAX_RATE) > 0) {
            throw Validate.invalid("rate is too large");
        }
        if (r.stripTrailingZeros().scale() > 10) {
            throw Validate.invalid("rate can have at most 10 decimal places");
        }
        return r;
    }

    @Transactional
    public RateView create(InsightsContext ctx, CreateRequest req) {
        ctx.requireSettingsWrite();
        String base = currency("base_currency", req.baseCurrency());
        String quote = currency("quote_currency", req.quoteCurrency());
        if (base.equals(quote)) {
            throw Validate.invalid("base_currency and quote_currency must differ");
        }
        BigDecimal rate = parseRate(req.rate());
        LocalDate date = Validate.date("rate_date", req.rateDate(), true);
        if (date.isAfter(ctx.today())) {
            throw Validate.invalid("rate_date cannot be in the future");
        }
        if (date.isBefore(EARLIEST)) {
            throw Validate.invalid("rate_date is too far in the past");
        }
        String note = Validate.text("source_note", req.sourceNote(), 1, 200, false);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into exchange_rates (id, tenant_id, base_currency, quote_currency, rate, rate_date, source, source_note, created_by, created_at)
                    values (:id, :t, :b, :q, :r, :d, 'USER_SUPPLIED', :n, :u, :now)
                    """).param("id", id).param("t", ctx.tenantId()).param("b", base).param("q", quote).param("r", rate).param("d", date).param("n", note).param("u", ctx.userId())
                    .param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "An active rate for " + base + "/" + quote + " on " + date + " already exists; void it first to enter a corrected one");
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXCHANGE_RATE_ADDED", "EXCHANGE_RATE", id, base + "/" + quote + " " + rate.toPlainString() + " on " + date + " (USER_SUPPLIED)");
        return one(ctx, id);
    }

    private RateView one(InsightsContext ctx, UUID id) {
        return jdbc.sql("select id, base_currency, quote_currency, rate, rate_date, source, source_note, status, void_reason, created_at from exchange_rates where tenant_id = :t and id = :id")
                .param("t", ctx.tenantId()).param("id", id).query(Row.class).optional().map(RateService::view).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    @Transactional
    public RateView voidRate(InsightsContext ctx, UUID id, String reason) {
        ctx.requireSettingsWrite();
        String why = Validate.text("reason", reason, 1, 200, true);
        RateView current = one(ctx, id);
        if (!"ACTIVE".equals(current.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "That rate is already voided");
        }
        jdbc.sql("update exchange_rates set status = 'VOIDED', void_reason = :r, voided_at = :now where tenant_id = :t and id = :id and status = 'ACTIVE'")
                .param("r", why).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXCHANGE_RATE_VOIDED", "EXCHANGE_RATE", id, "reason recorded");
        return one(ctx, id);
    }

    public List<RateView> list(InsightsContext ctx, String base, String quote, boolean includeVoided, int limit, int offset) {
        ctx.requireRegionalRead();
        String b = base == null || base.isBlank() ? null : currency("base", base);
        String q = quote == null || quote.isBlank() ? null : currency("quote", quote);
        int lim = Math.max(1, Math.min(200, limit));
        return jdbc.sql("""
                select id, base_currency, quote_currency, rate, rate_date, source, source_note, status, void_reason, created_at from exchange_rates
                where tenant_id = :t and (cast(:b as text) is null or base_currency = :b) and (cast(:q as text) is null or quote_currency = :q) and (:v or status = 'ACTIVE')
                order by rate_date desc, created_at desc, id limit :lim offset :off
                """).param("t", ctx.tenantId()).param("b", b).param("q", q).param("v", includeVoided).param("lim", lim).param("off", Math.max(0, offset)).query(Row.class).list()
                .stream().map(RateService::view).toList();
    }

    /** The rate that applies to {@code date} for the exact pair, with an honest status. Does not check permissions (callers already did). */
    public Lookup find(InsightsContext ctx, String base, String quote, LocalDate date) {
        if (base.equals(quote)) {
            return new Lookup("SAME", base, quote, date, BigDecimal.ONE, date, 0L, "NONE", null, "Same currency: no conversion needed");
        }
        return jdbc.sql("""
                select id, base_currency, quote_currency, rate, rate_date, source, source_note, status, void_reason, created_at from exchange_rates
                where tenant_id = :t and base_currency = :b and quote_currency = :q and status = 'ACTIVE' and rate_date <= :d order by rate_date desc limit 1
                """).param("t", ctx.tenantId()).param("b", base).param("q", quote).param("d", date).query(Row.class).optional().map(r -> {
            long age = ChronoUnit.DAYS.between(r.rateDate(), date);
            boolean stale = age > ctx.rateStaleAfterDays();
            return new Lookup(stale ? "STALE" : "APPLIED", base, quote, date, r.rate(), r.rateDate(), age, r.source(), r.id(),
                    stale ? "The latest rate is " + age + " day(s) older than " + date + ", beyond the " + ctx.rateStaleAfterDays() + "-day threshold" : null);
        }).orElseGet(() -> new Lookup("MISSING", base, quote, date, null, null, null, null, null, "No " + base + "/" + quote + " rate dated on or before " + date + " has been entered"));
    }

    public Lookup lookup(InsightsContext ctx, String baseRaw, String quoteRaw, String dateRaw) {
        ctx.requireRegionalRead();
        LocalDate date = dateRaw == null || dateRaw.isBlank() ? ctx.today() : Validate.date("date", dateRaw, true);
        return find(ctx, currency("base", baseRaw), currency("quote", quoteRaw), date);
    }

    /** amount x rate, rounded once, half-even, to the target currency's minor unit. Always computed from the ORIGINAL amount. */
    public static BigDecimal convert(BigDecimal original, BigDecimal rate, String targetCurrency) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        return original.multiply(rate).setScale(Currencies.exponentOrThrow(targetCurrency), RoundingMode.HALF_EVEN);
    }
}
