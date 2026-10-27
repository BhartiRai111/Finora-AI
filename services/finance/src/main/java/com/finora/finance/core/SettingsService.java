package com.finora.finance.core;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-profile settings. The time zone defines what "today" and a month boundary mean for this user. */
@Service
public class SettingsService {

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public SettingsService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> get(FinanceContext ctx) {
        ctx.require(Action.READ);
        return Map.of("timezone", ctx.zone().getId(), "today", ctx.today().toString(),
                "currencies", com.finora.finance.money.Currencies.codes());
    }

    @Transactional
    public Map<String, Object> update(FinanceContext ctx, String timezone) {
        ctx.require(Action.UPDATE);
        ZoneId zone;
        try {
            if (timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone.trim())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "timezone must be an IANA name such as Asia/Kolkata");
            }
            zone = ZoneId.of(timezone.trim());
        } catch (java.time.DateTimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "timezone must be an IANA name such as Asia/Kolkata");
        }
        jdbc.sql("""
                insert into finance_settings (tenant_id, timezone, updated_at) values (:t, :z, :now)
                on conflict (tenant_id) do update set timezone = :z, updated_at = :now
                """)
                .param("t", ctx.tenantId()).param("z", zone.getId())
                .param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
        audit.record(ctx, "SETTINGS_UPDATED", "SETTINGS", ctx.tenantId(), "timezone=" + zone.getId());
        FinanceContext updated = new FinanceContext(ctx.tenantId(), ctx.userId(), ctx.role(), zone, ctx.clock());
        return get(updated);
    }
}
