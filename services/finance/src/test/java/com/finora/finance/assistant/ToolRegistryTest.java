package com.finora.finance.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.authz.Role;
import com.finora.finance.assistant.ToolRegistry.Rejected;
import com.finora.finance.insights.InsightsContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The allowlist: what a call (from the router or from a model's plan) must satisfy before anything runs. */
class ToolRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);

    private static InsightsContext ctx(boolean business) {
        return new InsightsContext(business ? InsightsContext.Domain.BUSINESS : InsightsContext.Domain.PERSONAL, UUID.randomUUID(), UUID.randomUUID(), Role.OWNER, ZoneId.of("UTC"), CLOCK);
    }

    @Test
    void onlyTheNineReadOnlyToolsExistAndNoneIsAWriter() {
        assertThat(ToolRegistry.TOOLS.keySet()).containsExactly("period_summary", "top_categories", "expense_change", "cash_flow_trend", "anomalies", "forecast", "what_if",
                "overdue_invoices", "project_profitability");
        assertThat(ToolRegistry.TOOLS.keySet()).noneMatch(n -> n.matches(".*(create|delete|update|pay|send|cancel|write|set|import|void|issue).*"));
    }

    @Test
    void anyNameOutsideTheAllowlistIsRejected() {
        for (String name : List.of("delete_invoice", "run_sql", "", "PERIOD_SUMMARY", "period_summary; drop table", "../etc/passwd", "x".repeat(41), "period summary")) {
            assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), name, Map.of())).as(name).isInstanceOf(Rejected.class);
        }
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), null, Map.of())).isInstanceOf(Rejected.class);
    }

    @Test
    void identityWorkspaceAndQueryArgumentsAreNeverAccepted() {
        for (String key : List.of("workspace_id", "tenant_id", "space", "user_id", "role", "sql", "query", "url", "path", "command")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(key, UUID.randomUUID().toString());
            assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "period_summary", args)).as(key).isInstanceOf(Rejected.class).hasMessage("argument not allowed");
        }
    }

    @Test
    void valuesMustBeShortPlainScalars() {
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "period_summary", Map.of("from", List.of("2026-01-01")))).isInstanceOf(Rejected.class);
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "period_summary", Map.of("from", Map.of("a", "b")))).isInstanceOf(Rejected.class);
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "period_summary", Map.of("from", "x".repeat(41)))).isInstanceOf(Rejected.class);
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "period_summary", Map.of("from", "2026-01-01\n; drop"))).isInstanceOf(Rejected.class);
        assertThat(ToolRegistry.validate(ctx(false), "top_categories", Map.of("limit", 3)).args()).containsEntry("limit", "3");
    }

    @Test
    void toolsOfTheOtherKindOfWorkspaceAreRejected() {
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "overdue_invoices", Map.of())).isInstanceOf(Rejected.class).hasMessageContaining("not available");
        assertThatThrownBy(() -> ToolRegistry.validate(ctx(false), "project_profitability", Map.of())).isInstanceOf(Rejected.class);
        assertThat(ToolRegistry.validate(ctx(true), "overdue_invoices", Map.of()).name()).isEqualTo("overdue_invoices");
        assertThat(ToolRegistry.available(ctx(false))).extracting(ToolRegistry.Spec::name).doesNotContain("overdue_invoices", "project_profitability");
        assertThat(ToolRegistry.available(ctx(true))).hasSize(9);
    }
}
