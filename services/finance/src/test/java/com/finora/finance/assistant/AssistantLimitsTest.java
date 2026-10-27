package com.finora.finance.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Rate limiting per user and hour, tied to the (controllable) clock. */
@TestPropertySource(properties = "finora.finance.ai.questions-per-hour=3")
class AssistantLimitsTest extends AssistantTestSupport {

    @Test
    void questionsAreLimitedPerUserAndHourAndTheRefusalIsLoggedWithoutTheQuestion() throws Exception {
        Person p = person();
        for (int i = 0; i < 3; i++) {
            assertThat(ask(p, "How much did I spend last month?").status()).isEqualTo(200);
        }
        Resp limited = ask(p, "How much did I spend last month? xyzzy");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.text("detail")).contains("3 assistant questions per hour");
        assertThat(post(p, "/finance-assistant/what-if", Map.of("expense_change_percent", "5")).status()).as("structured requests share the limit").isEqualTo(429);
        assertThat(audit(p.space(), "AI_RATE_LIMITED")).isEqualTo(2);
        assertThat(jdbc.sql("select string_agg(coalesce(detail, ''), ' ') from finance_audit_events where tenant_id = :t").param("t", p.space()).query(String.class).single()).doesNotContain("xyzzy");
        // another user of another workspace is unaffected, and the same user is free again an hour later
        assertThat(ask(person(), "How much did I spend last month?").status()).isEqualTo(200);
        clock.advance(java.time.Duration.ofMinutes(61));
        assertThat(ask(p, "How much did I spend last month?").status()).isEqualTo(200);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
