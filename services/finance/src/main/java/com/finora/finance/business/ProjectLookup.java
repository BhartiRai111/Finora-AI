package com.finora.finance.business;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only project rows for the summaries (kept apart from {@link ProjectService} to avoid a dependency cycle). */
@Component
class ProjectLookup {

    private static final String SELECT = """
            select p.id, p.name, p.code, p.client_id, c.name as client_name, p.status, p.currency, p.budget_amount, p.start_date, p.end_date,
                   p.description, p.archived_at, p.version
            from projects p left join clients c on c.tenant_id = p.tenant_id and c.id = p.client_id
            """;

    private final JdbcClient jdbc;

    ProjectLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ProjectService.Row> all(BusinessContext ctx, boolean includeArchived) {
        return jdbc.sql(SELECT + " where p.tenant_id = :t " + (includeArchived ? "" : "and p.archived_at is null ") + "order by lower(p.name), p.id limit 501")
                .param("t", ctx.tenantId()).query(ProjectService.Row.class).list();
    }

    ProjectService.Row one(BusinessContext ctx, UUID id) {
        return jdbc.sql(SELECT + " where p.tenant_id = :t and p.id = :id").param("t", ctx.tenantId()).param("id", id).query(ProjectService.Row.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }
}
