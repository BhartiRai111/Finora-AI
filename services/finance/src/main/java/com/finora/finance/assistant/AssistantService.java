package com.finora.finance.assistant;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.assistant.AiConfiguration.AiLimits;
import com.finora.finance.assistant.AssistantModel.Ai;
import com.finora.finance.assistant.AssistantModel.Answer;
import com.finora.finance.assistant.AssistantModel.Built;
import com.finora.finance.assistant.AssistantModel.Section;
import com.finora.finance.assistant.AssistantModel.ToolResult;
import com.finora.finance.assistant.QuestionRouter.Routed;
import com.finora.finance.assistant.ToolRegistry.ToolCall;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.insights.InsightsContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The finance assistant: question -> one allowlisted read-only tool -> verified figures (+ an optional, validated AI explanation). It never
 * writes financial data. The only rows it writes are the usage ledger (rate limits) and local audit events; neither holds the question text.
 */
@Service
public class AssistantService {

    public record Supported(String tool, String example) {
    }

    public record Status(String domain, Ai ai, List<Supported> supported, Map<String, Object> limits) {
    }

    private final AssistantTools tools;
    private final AiGateway gateway;
    private final AssistantUsage usage;
    private final FinanceAudit audit;
    private final AiLimits limits;

    AssistantService(AssistantTools tools, AiGateway gateway, AssistantUsage usage, FinanceAudit audit, AiLimits limits) {
        this.tools = tools;
        this.gateway = gateway;
        this.usage = usage;
        this.audit = audit;
        this.limits = limits;
    }

    public Status status(InsightsContext ctx) {
        ctx.requireReports();
        return new Status(ctx.domain().name(), gateway.status(), ToolRegistry.available(ctx).stream().map(s -> new Supported(s.name(), s.example())).toList(),
                Map.of("question_max_chars", QuestionRouter.MAX_QUESTION, "questions_per_hour", limits.questionsPerHour(), "provider_calls_per_day", limits.providerCallsPerDay(),
                        "provider_timeout_ms", limits.timeout().toMillis(), "read_only", true));
    }

    // ---------------------------------------------------------------- free-text questions

    public Answer ask(InsightsContext ctx, String rawQuestion) {
        ctx.requireReports();
        String question = QuestionRouter.validate(rawQuestion);
        usage.enforceQuestionLimit(ctx);
        Routed routed = QuestionRouter.route(ctx, question);
        switch (routed.kind()) {
            case SENSITIVE -> {
                audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_REQUEST_REFUSED", "ASSISTANT", null, "sensitive or write request refused; question not stored");
                return finish(ctx, "refused", message(ctx, "UNSUPPORTED", "refused", routed.message()));
            }
            case NEEDS_CLARIFICATION -> {
                return finish(ctx, "clarification", clarification(ctx, "clarification", routed.message()));
            }
            case UNSUPPORTED -> {
                return finish(ctx, "unsupported", message(ctx, "UNSUPPORTED", "unsupported", routed.message()));
            }
            case UNKNOWN -> {
                Optional<AiGateway.Plan> plan = gateway.plan(ctx, question);
                if (plan.isEmpty()) {
                    return finish(ctx, "unsupported", message(ctx, "UNSUPPORTED", "unsupported", "I could not match that to something I can answer from your recorded data. " + examples(ctx)));
                }
                if (plan.get().clarify() != null) {
                    return finish(ctx, "clarification", clarification(ctx, "clarification", plan.get().clarify()));
                }
                ToolCall call;
                try {
                    call = ToolRegistry.validate(ctx, plan.get().tool(), plan.get().args());
                } catch (ToolRegistry.Rejected e) {
                    audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_TOOL_REJECTED", "ASSISTANT", null, "model proposed a call that is not allowed: " + e.getMessage());
                    return finish(ctx, "rejected", message(ctx, "UNSUPPORTED", "rejected", "I could not safely turn that into a supported, read-only lookup. " + examples(ctx)));
                }
                return finish(ctx, call.name(), execute(ctx, call, false));
            }
            default -> {
                return finish(ctx, routed.call().name(), execute(ctx, routed.call(), false));
            }
        }
    }

    // ---------------------------------------------------------------- structured requests (the web forms)

    /** One tool with explicit arguments. Bad arguments are a 422, as for any other API. */
    public Answer run(InsightsContext ctx, String tool, Map<String, ?> args) {
        ctx.requireReports();
        usage.enforceQuestionLimit(ctx);
        ToolCall call;
        try {
            call = ToolRegistry.validate(ctx, tool, args);
        } catch (ToolRegistry.Rejected e) {
            audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_TOOL_REJECTED", "ASSISTANT", null, "request used a call that is not allowed: " + e.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That request is not supported");
        }
        return finish(ctx, call.name(), execute(ctx, call, true));
    }

    // ---------------------------------------------------------------- internals

    private Answer execute(InsightsContext ctx, ToolCall call, boolean structured) {
        ToolResult result;
        try {
            result = tools.run(ctx, call);
        } catch (ApiException e) {
            if (!structured && e.code() == ErrorCode.VALIDATION_FAILED) {
                return clarification(ctx, call.name(), e.getMessage());
            }
            usage.record(ctx, "QUESTION", call.name(), null, "ERROR_" + e.code());
            throw e;
        }
        Ai ai = gateway.explain(ctx, call.name(), result);
        List<String> notices = new ArrayList<>();
        if (result.suspiciousLabels() > 0) {
            notices.add(result.suspiciousLabels() + " record label(s) in this answer contain text that looks like instructions. They are shown only as data, were never sent to any AI provider and had no effect.");
            audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_INJECTION_SUSPECTED", "ASSISTANT", null,
                    "instruction-like text found in " + result.suspiciousLabels() + " record label(s) while answering " + call.name() + "; content not logged");
        }
        usage.record(ctx, "QUESTION", call.name(), ai.provider(), result.status());
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "AI_QUESTION", "ASSISTANT", null, "tool=" + call.name() + " status=" + result.status() + " ai=" + ai.status());
        return new Answer(result.status(), call.name(), ctx.domain().name(), result.period(), result.summary(), result.clarification(),
                result.sections().stream().map(Built::section).toList(), result.assumptions(), result.limitations(), result.sources(), notices, ai);
    }

    /** Records usage for the branches that did not run a tool, and returns the answer. */
    private Answer finish(InsightsContext ctx, String intent, Answer answer) {
        if (!"ANSWERED".equals(answer.status()) && !"NO_DATA".equals(answer.status()) || answer.ai() == null || answer.sections().isEmpty()) {
            if (!"ANSWERED".equals(answer.status()) && !"NO_DATA".equals(answer.status())) {
                usage.record(ctx, "QUESTION", intent, null, answer.status());
            }
        }
        return answer;
    }

    private Answer message(InsightsContext ctx, String status, String intent, String text) {
        return new Answer(status, intent, ctx.domain().name(), null, text, null, List.of(), List.of(), List.of(), List.of(), List.of(), gateway.status());
    }

    private Answer clarification(InsightsContext ctx, String intent, String text) {
        return new Answer("NEEDS_CLARIFICATION", intent, ctx.domain().name(), null, null, text, List.<Section>of(), List.of(), List.of(), List.of(), List.of(), gateway.status());
    }

    private static String examples(InsightsContext ctx) {
        StringBuilder out = new StringBuilder("You can ask, for example: ");
        List<ToolRegistry.Spec> specs = ToolRegistry.available(ctx);
        for (int i = 0; i < specs.size(); i++) {
            out.append(i == 0 ? "" : " ").append('"').append(specs.get(i).example()).append('"');
        }
        return out.toString();
    }
}
