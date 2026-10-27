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
        String tz = provisional.business()
                ? jdbc.sql("select timezone from business_settings where tenant_id = :t").param("t", provisional.tenantId()).query(String.class).optional()
                        .orElse(BusinessSettingsService.DEFAULT_TIMEZONE)
                : jdbc.sql("select timezone from finance_settings where tenant_id = :t").param("t", provisional.tenantId()).query(String.class).optional().orElse("UTC");
        return new InsightsContext(provisional.domain(), provisional.tenantId(), provisional.userId(), provisional.role(), ZoneId.of(tz), clock);
    }
}
