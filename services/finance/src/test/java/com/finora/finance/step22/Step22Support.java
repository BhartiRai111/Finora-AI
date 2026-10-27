package com.finora.finance.step22;

import com.finora.finance.BusinessTestSupport;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared helpers for the Step 22 tests. Today is 2026-03-15T10:00Z. */
abstract class Step22Support extends BusinessTestSupport {

    @Value("${spring.flyway.url}") String migrationUrl;
    @Value("${spring.flyway.user}") String migrationUser;
    @Value("${spring.flyway.password:}") String migrationPassword;

    /** The migration (owner) role, used ONLY to arrange states the application role is forbidden to create, such as a plan that requires payment. */
    protected JdbcClient owner() {
        return JdbcClient.create(new DriverManagerDataSource(migrationUrl, migrationUser, migrationPassword));
    }

    protected Resp subPost(Biz b, java.util.UUID user, String role, String path, Object body) throws Exception {
        return as(b, user, role, "POST", path, body);
    }

    protected long audit(Object tenant, String type) {
        return jdbc.sql("select count(*) from finance_audit_events where tenant_id = :t and event_type = :e").param("t", tenant).param("e", type).query(Long.class).single();
    }

    protected Map<String, Object> env(String id, String type, Object workspace, long created, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("workspace_id", workspace.toString());
        m.put("created", created);
        m.putAll(extra);
        return m;
    }
}
