package com.finora.commons.outbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer-side dedupe (at-least-once delivery, effectively-once processing). The (consumer, event id) row is written
 * in the same transaction as the handler's changes, so a failed handler leaves no trace and can be redelivered.
 */
public class InboxService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InboxService(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return true if the handler ran, false if this event was already processed by this consumer */
    public boolean processOnce(String consumer, UUID eventId, Runnable handler) {
        Boolean ran = tx.execute(status -> {
            int inserted = jdbc.sql("""
                    insert into inbox_events (consumer_name, event_id, received_at) values (:c, :e, :now)
                    on conflict (consumer_name, event_id) do nothing
                    """)
                    .param("c", consumer).param("e", eventId)
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
            if (inserted == 0) {
                return false;
            }
            handler.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }
}
