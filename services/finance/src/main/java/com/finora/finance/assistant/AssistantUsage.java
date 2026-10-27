package com.finora.finance.assistant;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.assistant.AiConfiguration.AiLimits;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.insights.InsightsContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Rate limits and cost caps. The ledger holds no question text, prompts, answers or figures: only who, what kind, and the outcome. */
@Component
class AssistantUsage {

    private final JdbcClient jdbc;
    private final AiLimits limits;
    private final FinanceAudit audit;

    AssistantUsage(JdbcClient jdbc, AiLimits limits, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.audit = audit;
    }

    private long count(InsightsContext ctx, String kind, boolean perUser, Duration window) {
        OffsetDateTime since = OffsetDateTime.ofInstant(ctx.clock().instant().minus(window), ZoneOffset.UTC);
        return jdbc.sql("select count(*) from finance_ai_usage where tenant_id = :t and kind = :k and created_at > :s" + (perUser ? " and user_id = :u" : ""))
                .param("t", ctx.tenantId()).param("k", kind).param("s", since).param("u", ctx.userId()).query(Long.class).single();
    }

    /** At most {@code questionsPerHour} questions per user and hour; a refusal is itself logged as a security event (without the question). */
    void enforceQuestionLimit(InsightsContext ctx) {
        if (count(ctx, "QUESTION", true, Duration.ofHours(1)) >= limits.questionsPerHour()) {
            audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_RATE_LIMITED", "ASSISTANT", null, "questions per hour limit " + limits.questionsPerHour());
            throw new ApiException(ErrorCode.RATE_LIMITED, "You have reached the limit of " + limits.questionsPerHour() + " assistant questions per hour. Try again later.");
        }
    }

    /** The per-workspace daily cap on calls to an AI provider. */
    boolean providerCapReached(InsightsContext ctx) {
        return count(ctx, "PROVIDER_CALL", false, Duration.ofDays(1)) >= limits.providerCallsPerDay();
    }

    void record(InsightsContext ctx, String kind, String intent, String provider, String outcome) {
        jdbc.sql("insert into finance_ai_usage (id, tenant_id, user_id, kind, intent, provider, outcome, created_at) values (:id, :t, :u, :k, :i, :p, :o, :now)")
                .param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("u", ctx.userId()).param("k", kind).param("i", intent).param("p", provider).param("o", outcome)
                .param("now", OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)).update();
    }
}
