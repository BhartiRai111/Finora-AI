package com.finora.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Additional checks for services that own a PostgreSQL database: Flyway migrations ran under the
 * migrator role, the runtime role has no DDL rights, and the runtime role cannot connect to another
 * service's database (the owner's no-shared-database rule, enforced at the database level).
 */
public abstract class AbstractDatabaseServiceTest extends AbstractServiceTest {

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
}
