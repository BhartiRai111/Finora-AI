package com.finora.finance.insights;

import com.finora.commons.security.InternalPrincipal;
import com.finora.finance.business.BusinessSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves the {@link InsightsContext}; the time zone comes from the workspace's own settings (personal or business). */
@Component
public class InsightsAccess {

    private final JdbcClient jdbc;
    private final Clock clock;

    public InsightsAccess(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public InsightsContext resolve(HttpServletRequest request, UUID pathSpace) {
        return resolve(request, pathSpace, null);
    }

    /** @param wanted the only domain allowed here, or null for both */
    public InsightsContext resolve(HttpServletRequest request, UUID pathSpace, InsightsContext.Domain wanted) {
        InternalPrincipal p = (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
        InsightsContext provisional = InsightsContext.from(p, pathSpace, ZoneId.of("UTC"), clock, wanted);
        record Regional(String timezone, String displayCurrency, int fiscalStartMonth, int rateStaleAfterDays) {
        }
        String table = provisional.business() ? "business_settings" : "finance_settings";
        Regional r = jdbc.sql("select timezone, display_currency, fiscal_year_start_month, rate_stale_after_days from " + table + " where tenant_id = :t")
                .param("t", provisional.tenantId()).query((rs, i) -> new Regional(rs.getString(1), rs.getString(2) == null ? null : rs.getString(2).trim(), rs.getInt(3), rs.getInt(4)))
                .optional().orElse(new Regional(provisional.business() ? BusinessSettingsService.DEFAULT_TIMEZONE : "UTC", null, InsightsContext.DEFAULT_FISCAL_START_MONTH,
                        InsightsContext.DEFAULT_RATE_STALE_DAYS));
        return new InsightsContext(provisional.domain(), provisional.tenantId(), provisional.userId(), provisional.role(), ZoneId.of(r.timezone()), clock, r.fiscalStartMonth(),
                r.displayCurrency(), r.rateStaleAfterDays());
    }
}
