package com.finora.finance.assistant;

import com.finora.finance.insights.InsightsContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The allowlist of what the assistant can do. Every tool is read-only and answers from the existing deterministic services. A call, whether it
 * came from the question router or from a model's plan, is checked here first: unknown tool names, tools of the other kind of workspace, unknown
 * or identity-like arguments (workspace, tenant, user, sql, url) and non-scalar values are rejected. Identity and workspace never come from
 * arguments; they come from the verified token.
 */
final class ToolRegistry {

    record ToolCall(String name, Map<String, String> args) {
    }

    record Spec(String name, Set<String> args, boolean personal, boolean business, boolean fullDetail, String example) {
    }

    /** Raised for a call that is not allowed; the reason is a fixed phrase, never the rejected input. */
    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Rejected(String reason) {
            super(reason, null, false, false);
        }
    }

    static final Map<String, Spec> TOOLS = new LinkedHashMap<>();

    static {
        add("period_summary", Set.of("from", "to", "currency", "reporting_currency"), true, true, false, "How much did I spend last month?");
        add("top_categories", Set.of("from", "to", "limit", "currency"), true, true, false, "What are my top spending categories this year?");
        add("expense_change", Set.of("from", "to", "currency"), true, true, false, "Why did my expenses rise compared with the previous period?");
        add("cash_flow_trend", Set.of("months", "currency"), true, true, false, "How did my cash flow change over the last 3 months?");
        add("anomalies", Set.of("from", "to", "currency"), true, true, true, "Show unusual transactions last month");
        add("forecast", Set.of("metric", "lookback_months", "horizon_months"), true, true, false, "Forecast my next 3 months");
        add("what_if", Set.of("expense_change_percent", "revenue_change_percent", "horizon_months", "lookback_months"), true, true, false,
                "What if my expenses increase by 10%?");
        add("overdue_invoices", Set.of(), false, true, false, "Which invoices are overdue?");
        add("gst_summary", Set.of("from", "to"), false, true, false, "Show my GST summary for last month");
        add("project_profitability", Set.of("from", "to", "order", "limit"), false, true, false, "Which projects have the lowest profit?");
    }

    private static void add(String name, Set<String> args, boolean personal, boolean business, boolean fullDetail, String example) {
        TOOLS.put(name, new Spec(name, args, personal, business, fullDetail, example));
    }

    private ToolRegistry() {
    }

    static List<Spec> available(InsightsContext ctx) {
        return TOOLS.values().stream().filter(s -> ctx.business() ? s.business() : s.personal()).toList();
    }

    static ToolCall validate(InsightsContext ctx, String name, Map<String, ?> raw) {
        if (name == null || name.length() > 40 || !name.matches("[a-z_]+")) {
            throw new Rejected("unknown tool");
        }
        Spec spec = TOOLS.get(name);
        if (spec == null) {
            throw new Rejected("unknown tool");
        }
        if (!(ctx.business() ? spec.business() : spec.personal())) {
            throw new Rejected("tool not available in this kind of workspace");
        }
        Map<String, String> args = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, ?> e : raw.entrySet()) {
                if (!spec.args().contains(e.getKey())) {
                    throw new Rejected("argument not allowed");
                }
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                    throw new Rejected("argument must be a plain value");
                }
                String s = v.toString().trim();
                if (s.length() > 40 || s.chars().anyMatch(Character::isISOControl)) {
                    throw new Rejected("argument value not allowed");
                }
                if (!s.isEmpty()) {
                    args.put(e.getKey(), s);
                }
            }
        }
        return new ToolCall(name, args);
    }
}
