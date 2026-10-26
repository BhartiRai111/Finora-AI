package com.finora.commons.outbox;

import com.finora.commons.correlation.CorrelationId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox: persistence only. {@link #append} must run inside the transaction that changes the business
 * data, so an event exists if and only if the change committed. No relay or broker is implemented (PD-04 is open);
 * a future relay reads {@link #pending} and calls {@link #markPublished}.
 */
public class OutboxService {

    public record OutboxEvent(UUID id, String aggregateType, String aggregateId, String eventType, String payload,
            String correlationId) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutboxService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public UUID append(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox append requires the business transaction");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at)
                values (:id, :at, :ai, :et, cast(:payload as jsonb), :cid, :now)
                """)
                .param("id", id).param("at", aggregateType).param("ai", aggregateId).param("et", eventType)
                .param("payload", payloadJson).param("cid", CorrelationId.current())
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        return id;
    }

    public List<OutboxEvent> pending(int limit) {
        return jdbc.sql("""
                select id, aggregate_type, aggregate_id, event_type, payload::text, correlation_id
                from outbox_events where published_at is null order by created_at, id limit :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new OutboxEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    public void markPublished(UUID id) {
        jdbc.sql("update outbox_events set published_at = :now where id = :id and published_at is null")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("id", id).update();
    }
}
