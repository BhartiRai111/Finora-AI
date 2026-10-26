package com.finora.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.idempotency.IdempotencyService;
import com.finora.commons.idempotency.IdempotencyService.StoredResponse;
import com.finora.commons.outbox.InboxService;
import com.finora.commons.outbox.OutboxService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Additional checks for services that own a PostgreSQL database: Flyway migrations ran under the
 * migrator role, the runtime role has no DDL rights, and the runtime role cannot connect to another
 * service's database (the owner's no-shared-database rule, enforced at the database level).
 */
public abstract class AbstractDatabaseServiceTest extends AbstractInternalServiceTest {

    @Autowired
    protected IdempotencyService idempotency;

    @Autowired
    protected OutboxService outbox;

    @Autowired
    protected InboxService inbox;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    private String url() {
        return env.getRequiredProperty("spring.datasource.url");
    }

    private Connection connectAsRuntimeRole(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl,
                env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
    }

    @Test
    void flywayBaselineMigrationWasApplied() throws Exception {
        try (Connection c = DriverManager.getConnection(
                        env.getRequiredProperty("spring.flyway.url"),
                        env.getRequiredProperty("spring.flyway.user"),
                        env.getRequiredProperty("spring.flyway.password"));
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select count(*) from flyway_schema_history where success and version = '1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void runtimeRoleCannotCreateObjects() throws Exception {
        try (Connection c = connectAsRuntimeRole(url()); Statement s = c.createStatement()) {
            // Run inside a transaction that is always rolled back, so a misconfigured role can never leave a table behind.
            c.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> s.execute("create table must_not_exist(id int)"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void runtimeRoleCannotConnectToAnotherServicesDatabase() {
        String own = url();
        String other = own.contains("/finora_identity_access")
                ? own.replace("/finora_identity_access", "/finora_finance")
                : own.replaceAll("/finora_[a-z_]+", "/finora_identity_access");
        assertThat(other).isNotEqualTo(own);
        assertThatThrownBy(() -> connectAsRuntimeRole(other).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    // ---- Step 14: idempotency, outbox, inbox (real PostgreSQL, real transactions) ----

    @Test
    void communicationMigrationWasApplied() throws Exception {
        try (Connection c = DriverManager.getConnection(
                        env.getRequiredProperty("spring.flyway.url"),
                        env.getRequiredProperty("spring.flyway.user"),
                        env.getRequiredProperty("spring.flyway.password"));
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select count(*) from flyway_schema_history where success and version = '2'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void idempotentRetryRunsTheWorkOnceAndReplaysTheResponse() {
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();
        String hash = IdempotencyService.hashRequest("POST", "/x", "a".getBytes());
        StoredResponse first = idempotency.execute("scope-a", key, hash,
                () -> new StoredResponse(201, "{\"n\":" + executions.incrementAndGet() + "}"));
        StoredResponse second = idempotency.execute("scope-a", key, hash,
                () -> new StoredResponse(201, "{\"n\":" + executions.incrementAndGet() + "}"));
        assertThat(executions).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void sameKeyWithADifferentRequestIsRefused() {
        String key = UUID.randomUUID().toString();
        idempotency.execute("scope-a", key, IdempotencyService.hashRequest("POST", "/x", "one".getBytes()),
                () -> new StoredResponse(200, "{}"));
        assertThatThrownBy(() -> idempotency.execute("scope-a", key,
                IdempotencyService.hashRequest("POST", "/x", "two".getBytes()), () -> new StoredResponse(200, "{}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void sameKeyInADifferentScopeIsIndependent() {
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();
        String hash = IdempotencyService.hashRequest("POST", "/x", new byte[0]);
        idempotency.execute("tenant-1", key, hash, () -> new StoredResponse(200, "{" + executions.incrementAndGet() + "}"));
        idempotency.execute("tenant-2", key, hash, () -> new StoredResponse(200, "{" + executions.incrementAndGet() + "}"));
        assertThat(executions).hasValue(2);
    }

    @Test
    void concurrentRequestsWithTheSameKeyExecuteExactlyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hashRequest("POST", "/x", new byte[0]);
        AtomicInteger executions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<StoredResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<StoredResponse> call = () -> idempotency.execute("scope-c", key, hash, () -> {
                    int n = executions.incrementAndGet();
                    sleep(300);
                    return new StoredResponse(201, "{\"n\":" + n + "}");
                });
                futures.add(pool.submit(call));
            }
            for (Future<StoredResponse> f : futures) {
                assertThat(f.get()).isEqualTo(new StoredResponse(201, "{\"n\":1}"));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(executions).hasValue(1);
    }

    @Test
    void failedWorkReleasesTheKeyAndRollsBackEverythingInTheSameTransaction() {
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hashRequest("POST", "/x", new byte[0]);
        assertThatThrownBy(() -> idempotency.execute("scope-r", key, hash, () -> {
            outbox.append("Thing", "1", "thing.created", "{}");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("select count(*) from idempotency_keys where scope = 'scope-r' and idem_key = :k")
                .param("k", key).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from outbox_events where aggregate_type = 'Thing' and aggregate_id = '1'")
                .query(Long.class).single()).isZero();
        AtomicInteger executions = new AtomicInteger();
        idempotency.execute("scope-r", key, hash, () -> {
            executions.incrementAndGet();
            return new StoredResponse(200, "{}");
        });
        assertThat(executions).hasValue(1);
    }

    @Test
    void expiredKeysMayBeReused() {
        java.time.Clock[] now = {java.time.Clock.fixed(Instant.now(), java.time.ZoneOffset.UTC)};
        java.time.Clock moving = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0].instant();
            }
        };
        IdempotencyService shortLived = new IdempotencyService(jdbc, tx, moving, Duration.ofMinutes(1));
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hashRequest("POST", "/x", new byte[0]);
        AtomicInteger executions = new AtomicInteger();
        shortLived.execute("scope-e", key, hash, () -> new StoredResponse(200, "{" + executions.incrementAndGet() + "}"));
        shortLived.execute("scope-e", key, hash, () -> new StoredResponse(200, "{" + executions.incrementAndGet() + "}"));
        assertThat(executions).hasValue(1);
        now[0] = java.time.Clock.fixed(now[0].instant().plus(Duration.ofMinutes(5)), java.time.ZoneOffset.UTC);
        shortLived.execute("scope-e", key, hash, () -> new StoredResponse(200, "{" + executions.incrementAndGet() + "}"));
        assertThat(executions).hasValue(2);
    }

    @Test
    void invalidIdempotencyKeysAreRefused() {
        String hash = IdempotencyService.hashRequest("POST", "/x", new byte[0]);
        assertThatThrownBy(() -> idempotency.execute("s", "", hash, () -> new StoredResponse(200, "{}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> idempotency.execute("s", "k".repeat(129), hash, () -> new StoredResponse(200, "{}")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void outboxEventExistsIfAndOnlyIfTheBusinessTransactionCommitted() {
        String aggregate = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> outbox.append("Thing", aggregate, "thing.committed", "{\"a\":1}"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.append("Thing", aggregate, "thing.rolled-back", "{}");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        List<String> types = jdbc.sql("select event_type from outbox_events where aggregate_id = :a order by created_at")
                .param("a", aggregate).query(String.class).list();
        assertThat(types).containsExactly("thing.committed");
        assertThat(outbox.pending(1000)).anySatisfy(e -> assertThat(e.aggregateId()).isEqualTo(aggregate));
    }

    @Test
    void outboxAppendOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.append("Thing", "1", "thing.created", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedOutboxEventsLeaveThePendingList() {
        String aggregate = UUID.randomUUID().toString();
        UUID id = tx.execute(s -> outbox.append("Thing", aggregate, "thing.created", "{}"));
        outbox.markPublished(id);
        assertThat(outbox.pending(1000)).noneSatisfy(e -> assertThat(e.id()).isEqualTo(id));
    }

    @Test
    void inboxDeliversAnEventToOneConsumerOnce() {
        UUID event = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();
        assertThat(inbox.processOnce("consumer-a", event, handled::incrementAndGet)).isTrue();
        assertThat(inbox.processOnce("consumer-a", event, handled::incrementAndGet)).isFalse();
        assertThat(inbox.processOnce("consumer-b", event, handled::incrementAndGet)).isTrue();
        assertThat(handled).hasValue(2);
    }

    @Test
    void inboxForgetsAnEventWhoseHandlerFailedSoItCanBeRedelivered() {
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> inbox.processOnce("consumer-f", event, () -> {
            throw new IllegalStateException("handler failed");
        })).isInstanceOf(IllegalStateException.class);
        AtomicInteger handled = new AtomicInteger();
        assertThat(inbox.processOnce("consumer-f", event, handled::incrementAndGet)).isTrue();
        assertThat(handled).hasValue(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
