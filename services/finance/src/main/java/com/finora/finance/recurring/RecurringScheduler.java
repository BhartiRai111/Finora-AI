package com.finora.finance.recurring;

import com.finora.finance.core.FinanceContext;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates due recurring transactions in the background. It is safe to run on several replicas at once: each profile's
 * rules are locked while they are processed and every occurrence is unique in the database, so nothing is created twice.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty(name = "finora.finance.recurring.scheduler-enabled", matchIfMissing = true)
public class RecurringScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringScheduler.class);

    private final JdbcClient jdbc;
    private final RecurringService recurring;
    private final Clock clock;

    public RecurringScheduler(JdbcClient jdbc, RecurringService recurring, Clock clock) {
        this.jdbc = jdbc;
        this.recurring = recurring;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${finora.finance.recurring.initial-delay:PT1M}", fixedDelayString = "${finora.finance.recurring.interval:PT1H}")
    public void run() {
        List<UUID> tenants = jdbc.sql("select distinct tenant_id from recurring_rules where active").query(UUID.class).list();
        for (UUID tenant : tenants) {
            try {
                String tz = jdbc.sql("select timezone from finance_settings where tenant_id = :t").param("t", tenant).query(String.class)
                        .optional().orElse("UTC");
                recurring.generateDueAsSystem(FinanceContext.system(tenant, ZoneId.of(tz), clock));
            } catch (RuntimeException e) {
                LOG.warn("Recurring generation failed for one profile ({})", e.getClass().getSimpleName());
            }
        }
    }
}
