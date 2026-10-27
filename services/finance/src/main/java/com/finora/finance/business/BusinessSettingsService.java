package com.finora.finance.business;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.money.Currencies;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company profile subset that Step 18 needs: time zone (overdue flips at the business's midnight), base currency (the
 * default for new invoices; never used to convert anything) and the invoice number format. Numbering is
 * prefix + zero-padded sequence; the sequence only moves forward, and a number is assigned when an invoice is issued.
 */
@Service
public class BusinessSettingsService {

    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";
    private static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9/_-]{0,12}$");

    public record Settings(String timezone, String baseCurrency, String companyName, String invoicePrefix, int invoicePadding,
            long invoiceNextNumber, int defaultTermsDays, String nextInvoiceNumberPreview, String today) {
    }

    public record UpdateRequest(String timezone, String baseCurrency, String companyName, String invoicePrefix, Integer invoicePadding,
            Long invoiceNextNumber, Integer defaultTermsDays) {
    }

    record Row(String timezone, String baseCurrency, String companyName, String invoicePrefix, int invoicePadding,
            long invoiceNextNumber, int defaultTermsDays) {
    }

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public BusinessSettingsService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public static String formatNumber(String prefix, int padding, long n) {
        String digits = Long.toString(n);
        return prefix + "0".repeat(Math.max(0, padding - digits.length())) + digits;
    }

    /** Makes sure the settings row exists (defaults) and returns it. */
    Row ensure(BusinessContext ctx) {
        jdbc.sql("insert into business_settings (tenant_id, updated_at) values (:t, :now) on conflict (tenant_id) do nothing")
                .param("t", ctx.tenantId()).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
        return jdbc.sql("""
                select timezone, base_currency, company_name, invoice_prefix, invoice_padding, invoice_next_number, default_terms_days
                from business_settings where tenant_id = :t
                """).param("t", ctx.tenantId()).query(Row.class).single();
    }

    private Settings view(BusinessContext ctx, Row r) {
        return new Settings(r.timezone(), r.baseCurrency().trim(), r.companyName(), r.invoicePrefix(), r.invoicePadding(), r.invoiceNextNumber(),
                r.defaultTermsDays(), formatNumber(r.invoicePrefix(), r.invoicePadding(), r.invoiceNextNumber()), ctx.today().toString());
    }

    @Transactional
    public Settings get(BusinessContext ctx) {
        ctx.requireAll(Resource.COMPANY_SETTINGS, Action.READ);
        return view(ctx, ensure(ctx));
    }

    /** Settings an invoice author needs (terms, base currency) without exposing company settings to every role. */
    Row defaults(BusinessContext ctx) {
        return ensure(ctx);
    }

    @Transactional
    public Settings update(BusinessContext ctx, UpdateRequest req) {
        ctx.requireAll(Resource.COMPANY_SETTINGS, Action.UPDATE);
        Row current = ensure(ctx);
        String tz = current.timezone();
        if (req.timezone() != null) {
            if (!ZoneId.getAvailableZoneIds().contains(req.timezone().trim())) {
                throw Validate.invalid("timezone must be an IANA name such as Asia/Kolkata");
            }
            tz = req.timezone().trim();
        }
        String currency = current.baseCurrency().trim();
        if (req.baseCurrency() != null) {
            currency = req.baseCurrency().trim().toUpperCase(Locale.ROOT);
            if (!Currencies.supported(currency)) {
                throw Validate.invalid("base_currency is not supported");
            }
        }
        String company = req.companyName() == null ? current.companyName() : Validate.text("company_name", req.companyName(), 1, 120, false);
        String prefix = current.invoicePrefix();
        if (req.invoicePrefix() != null) {
            prefix = req.invoicePrefix().trim();
            if (!PREFIX.matcher(prefix).matches()) {
                throw Validate.invalid("invoice_prefix may have up to 12 letters, digits, '-', '_' or '/'");
            }
        }
        int padding = req.invoicePadding() == null ? current.invoicePadding() : req.invoicePadding();
        if (padding < 1 || padding > 10) {
            throw Validate.invalid("invoice_padding must be between 1 and 10");
        }
        long next = req.invoiceNextNumber() == null ? current.invoiceNextNumber() : req.invoiceNextNumber();
        if (next < current.invoiceNextNumber()) {
            // A number that was already used (or skipped) is never handed out again.
            throw new ApiException(ErrorCode.CONFLICT, "invoice_next_number can only move forward");
        }
        if (next > 999_999_999_999L) {
            throw Validate.invalid("invoice_next_number is too large");
        }
        int terms = req.defaultTermsDays() == null ? current.defaultTermsDays() : req.defaultTermsDays();
        if (terms < 0 || terms > 365) {
            throw Validate.invalid("default_terms_days must be between 0 and 365");
        }
        jdbc.sql("""
                update business_settings set timezone = :tz, base_currency = :cur, company_name = :co, invoice_prefix = :p, invoice_padding = :pad,
                       invoice_next_number = :n, default_terms_days = :terms, updated_at = :now where tenant_id = :t
                """).param("tz", tz).param("cur", currency).param("co", company).param("p", prefix).param("pad", padding).param("n", next)
                .param("terms", terms).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "BUSINESS_SETTINGS_UPDATED", "BUSINESS_SETTINGS", ctx.tenantId(),
                "timezone=" + tz + " base_currency=" + currency + " invoice_prefix=" + prefix + " padding=" + padding + " next=" + next + " terms=" + terms);
        return view(ctx, ensure(ctx));
    }

}
