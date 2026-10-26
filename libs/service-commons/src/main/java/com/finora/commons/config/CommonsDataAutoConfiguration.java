package com.finora.commons.config;

import com.finora.commons.idempotency.IdempotencyService;
import com.finora.commons.outbox.InboxService;
import com.finora.commons.outbox.OutboxService;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Idempotency, outbox and inbox persistence for services that own a database. Tables come from each service's V2 migration. */
@AutoConfiguration(after = CommonsAutoConfiguration.class, afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"})
@ConditionalOnClass({DataSource.class, JdbcClient.class})
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
public class CommonsDataAutoConfiguration {

    @Bean
    IdempotencyService idempotencyService(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        return new IdempotencyService(jdbc, tx, clock, Duration.ofHours(24));
    }

    @Bean
    OutboxService outboxService(JdbcClient jdbc, Clock clock) {
        return new OutboxService(jdbc, clock);
    }

    @Bean
    InboxService inboxService(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        return new InboxService(jdbc, tx, clock);
    }
}
