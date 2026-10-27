package com.finora.finance.core;

import com.finora.commons.correlation.CorrelationId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local append-only audit log for personal-finance changes (TX-10), written in the same transaction as the change.
 * PD-15 (the approved audit architecture) is still pending: this makes no tamper-evidence or compliance claim.
 * Details hold field names and before/after values of the user's own data, never credentials.
 */
@Component
public class FinanceAudit {

    private final JdbcClient jdbc;

    public FinanceAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(FinanceContext ctx, String type, String targetType, Object targetId, String detail) {
        record(ctx.tenantId(), ctx.userId(), ctx.clock(), type, targetType, targetId, detail);
    }

    public void record(UUID tenantId, UUID actorId, java.time.Clock clock, String type, String targetType, Object targetId, String detail) {
        jdbc.sql("""
                insert into finance_audit_events (id, tenant_id, actor_id, event_type, target_type, target_id, detail, request_id, created_at)
                values (:id, :t, :a, :type, :tt, :ti, :d, :r, :now)
                """)
                .param("id", UUID.randomUUID()).param("t", tenantId).param("a", actorId).param("type", type)
                .param("tt", targetType).param("ti", targetId == null ? null : targetId.toString())
                .param("d", detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail))
                .param("r", CorrelationId.current())
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }
}
