package com.finora.finance.core;

import com.finora.commons.security.InternalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves the caller's {@link FinanceContext}. Identity and tenant come from the token; the time zone from settings. */
@Component
public class FinanceAccess {

    private final JdbcClient jdbc;
    private final Clock clock;

    public FinanceAccess(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public FinanceContext resolve(HttpServletRequest request, UUID pathSpace) {
        InternalPrincipal p = (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
        // First pass with UTC only to validate tenant, scope and role before touching the database.
        FinanceContext provisional = FinanceContext.from(p, pathSpace, ZoneId.of("UTC"), clock);
        String tz = jdbc.sql("select timezone from finance_settings where tenant_id = :t").param("t", provisional.tenantId())
                .query(String.class).optional().orElse("UTC");
        return new FinanceContext(provisional.tenantId(), provisional.userId(), provisional.role(), ZoneId.of(tz), clock);
    }
}
