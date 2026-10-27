package com.finora.finance.business;

import com.finora.commons.security.InternalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves the caller's {@link BusinessContext}: identity and workspace from the token, time zone from business settings. */
@Component
public class BusinessAccess {

    private final JdbcClient jdbc;
    private final Clock clock;

    public BusinessAccess(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public BusinessContext resolve(HttpServletRequest request, UUID pathSpace) {
        InternalPrincipal p = (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
        BusinessContext provisional = BusinessContext.from(p, pathSpace, ZoneId.of("UTC"), clock);
        String tz = jdbc.sql("select timezone from business_settings where tenant_id = :t").param("t", provisional.tenantId())
                .query(String.class).optional().orElse(BusinessSettingsService.DEFAULT_TIMEZONE);
        return new BusinessContext(provisional.tenantId(), provisional.userId(), provisional.role(), ZoneId.of(tz), clock);
    }
}
