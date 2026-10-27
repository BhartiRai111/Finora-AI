package com.finora.finance.regional;

import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regional preferences of one workspace (personal profile or business). They shape how figures and dates are DISPLAYED and where period
 * boundaries fall; they never change a stored amount, currency or date. Changing the time zone changes what "today" and a month boundary mean
 * from then on; records keep the calendar dates they were entered with. Defaults reproduce the behaviour from before these settings existed.
 */
@Service
public class RegionalService {

    public static final Set<String> LOCALES = Set.of("en-IN", "en-US", "en-GB", "en-AU", "en-CA", "en-SG", "hi-IN", "de-DE", "fr-FR", "es-ES", "ja-JP", "pt-BR");
    public static final List<String> DATE_FORMATS = List.of("ISO", "DMY", "MDY");
    public static final List<String> PERIODS = List.of("MONTH", "QUARTER", "FISCAL_YEAR");

    public record UpdateRequest(String timezone, String displayCurrency, String locale, String dateFormat, Integer fiscalYearStartMonth, String reportingPeriod,
            Integer rateStaleAfterDays, Boolean clearDisplayCurrency, Boolean clearLocale, Boolean clearDateFormat) {
    }

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public RegionalService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private String table(InsightsContext ctx) {
        return ctx.business() ? "business_settings" : "finance_settings";
    }

    private void ensure(InsightsContext ctx) {
        jdbc.sql("insert into " + table(ctx) + " (tenant_id, updated_at) values (:t, :now) on conflict (tenant_id) do nothing")
                .param("t", ctx.tenantId()).param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
    }

    public Map<String, Object> get(InsightsContext ctx) {
        ctx.requireRegionalRead();
        ensure(ctx);
        Map<String, Object> row = jdbc.sql("select timezone, display_currency, locale, date_format, fiscal_year_start_month, reporting_period, rate_stale_after_days"
                + (ctx.business() ? ", base_currency" : ", null::char(3) as base_currency") + " from " + table(ctx) + " where tenant_id = :t").param("t", ctx.tenantId()).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", ctx.domain().name());
        out.put("timezone", row.get("timezone"));
        out.put("today", ctx.today().toString());
        out.put("display_currency", trim(row.get("display_currency")));
        out.put("base_currency", trim(row.get("base_currency")));
        out.put("locale", row.get("locale"));
        out.put("date_format", row.get("date_format"));
        out.put("fiscal_year_start_month", row.get("fiscal_year_start_month"));
        out.put("reporting_period", row.get("reporting_period"));
        out.put("rate_stale_after_days", row.get("rate_stale_after_days"));
        out.put("supported", Map.of("currencies", Currencies.codes(), "locales", new java.util.TreeSet<>(LOCALES), "date_formats", DATE_FORMATS, "reporting_periods", PERIODS));
        out.put("notes", List.of("These are display and period preferences. Stored amounts, currencies and dates are never changed by them.",
                "Changing the time zone affects what 'today' and period boundaries mean from now on; existing records keep their dates."));
        return out;
    }

    private static String trim(Object v) {
        return v == null ? null : v.toString().trim();
    }

    @Transactional
    public Map<String, Object> update(InsightsContext ctx, UpdateRequest req) {
        ctx.requireSettingsWrite();
        ensure(ctx);
        Map<String, Object> current = get(ctx);
        String tz = (String) current.get("timezone");
        if (req.timezone() != null) {
            String z = req.timezone().trim();
            if (!ZoneId.getAvailableZoneIds().contains(z)) {
                throw Validate.invalid("timezone must be an IANA name such as Asia/Kolkata");
            }
            tz = z;
        }
        String display = (String) current.get("display_currency");
        if (Boolean.TRUE.equals(req.clearDisplayCurrency())) {
            display = null;
        } else if (req.displayCurrency() != null) {
            display = req.displayCurrency().trim().toUpperCase(Locale.ROOT);
            if (!Currencies.supported(display)) {
                throw Validate.invalid("display_currency is not a supported currency");
            }
        }
        String locale = (String) current.get("locale");
        if (Boolean.TRUE.equals(req.clearLocale())) {
            locale = null;
        } else if (req.locale() != null) {
            locale = req.locale().trim();
            if (!LOCALES.contains(locale)) {
                throw Validate.invalid("locale must be one of " + new java.util.TreeSet<>(LOCALES));
            }
        }
        String dateFormat = (String) current.get("date_format");
        if (Boolean.TRUE.equals(req.clearDateFormat())) {
            dateFormat = null;
        } else if (req.dateFormat() != null) {
            dateFormat = req.dateFormat().trim().toUpperCase(Locale.ROOT);
            if (!DATE_FORMATS.contains(dateFormat)) {
                throw Validate.invalid("date_format must be one of " + DATE_FORMATS);
            }
        }
        int fiscal = req.fiscalYearStartMonth() == null ? (Integer) current.get("fiscal_year_start_month") : req.fiscalYearStartMonth();
        if (fiscal < 1 || fiscal > 12) {
            throw Validate.invalid("fiscal_year_start_month must be between 1 (January) and 12 (December)");
        }
        String period = (String) current.get("reporting_period");
        if (req.reportingPeriod() != null) {
            period = req.reportingPeriod().trim().toUpperCase(Locale.ROOT);
            if (!PERIODS.contains(period)) {
                throw Validate.invalid("reporting_period must be one of " + PERIODS);
            }
        }
        int stale = req.rateStaleAfterDays() == null ? (Integer) current.get("rate_stale_after_days") : req.rateStaleAfterDays();
        if (stale < 0 || stale > 365) {
            throw Validate.invalid("rate_stale_after_days must be between 0 and 365");
        }
        jdbc.sql("update " + table(ctx) + " set timezone = :tz, display_currency = :dc, locale = :lo, date_format = :df, fiscal_year_start_month = :fy, reporting_period = :rp, "
                + "rate_stale_after_days = :st, updated_at = :now where tenant_id = :t")
                .param("tz", tz).param("dc", display).param("lo", locale).param("df", dateFormat).param("fy", fiscal).param("rp", period).param("st", stale)
                .param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "REGIONAL_SETTINGS_UPDATED", "SETTINGS", ctx.tenantId(),
                "timezone=" + tz + " display_currency=" + display + " locale=" + locale + " date_format=" + dateFormat + " fiscal_start=" + fiscal + " period=" + period + " stale_days=" + stale);
        InsightsContext updated = new InsightsContext(ctx.domain(), ctx.tenantId(), ctx.userId(), ctx.role(), ZoneId.of(tz), ctx.clock(), fiscal, display, stale);
        return get(updated);
    }

    /** The fiscal-year and fiscal-quarter ranges for today, from this workspace's own settings. */
    public Map<String, Object> periods(InsightsContext ctx) {
        ctx.requireRegionalRead();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", ctx.today().toString());
        out.put("fiscal_year_start_month", ctx.fiscalStartMonth());
        Map<String, Object> ranges = new LinkedHashMap<>();
        FiscalPeriods.presets(ctx.today(), ctx.fiscalStartMonth()).forEach((k, r) ->
                ranges.put(k, Map.of("from", r.from().toString(), "to", r.to().toString(), "end", r.end().toString())));
        out.put("periods", ranges);
        out.put("notes", List.of("'to' is capped at today for a period that is still running; 'end' is the last day of the period."));
        return out;
    }
}
