package com.finora.finance.assistant;

import com.finora.finance.assistant.ToolRegistry.ToolCall;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question into one allowlisted tool call by fixed rules. No model is involved: the router decides the tool, the dates and the currency,
 * and says so in the answer. A question it cannot place is NOT guessed: it gets a clarification or an "unsupported" reply (and, only when a
 * provider is configured, the assistant may ask the provider to map it, with the result checked like any other call by {@link ToolRegistry}).
 */
final class QuestionRouter {

    enum Kind { ROUTED, NEEDS_CLARIFICATION, UNSUPPORTED, UNKNOWN, SENSITIVE }

    record Routed(Kind kind, ToolCall call, String message) {
        static Routed of(String name, Map<String, String> args) {
            return new Routed(Kind.ROUTED, new ToolCall(name, args), null);
        }

        static Routed clarify(String message) {
            return new Routed(Kind.NEEDS_CLARIFICATION, null, message);
        }
    }

    static final int MAX_QUESTION = 500;

    private static final Pattern SENSITIVE = Pattern.compile("(?i)api[\\s_-]?keys?|passwords?|secrets?|credentials?|session\\s+tokens?|\\btokens?\\b|private\\s+keys?|system\\s+prompt|"
            + "(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+|previous\\s+|prior\\s+|above\\s+)*(instructions?|rules?|prompts?)|"
            + "(other|another|every|all)\\s+(users?|workspaces?|customers?|accounts?\\s+of\\s+others|tenants?)|someone\\s+else|\\bsql\\b|\\bshell\\b|\\bcurl\\b|\\bwget\\b|"
            + "(delete|remove|drop|cancel|pay|transfer|send|edit|change|update|file)\\s+((my|the|all|every|a)\\s+)*(transactions?|invoices?|payments?|subscriptions?|budgets?|records?|taxes|tax|money)|"
            + "(file|submit|lodge|e-?file|upload)\\s+((my|the|our|a|an|all)\\s+)*(gst|gstr[- ]?\\w*|returns?|tax\\s+returns?)|(sync|push|send|export)\\s+(my\\s+|the\\s+|all\\s+)*(data|books|invoices|transactions)\\s+(to|into|with)\\s+\\w+");
    private static final Pattern DATES = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LAST_N = Pattern.compile("(?:last|past|previous)\\s+(\\d{1,3})\\s+(day|week|month|year)s?");
    private static final Pattern MONTH_YEAR = Pattern.compile("\\b(?:in|during|for|of)\\s+(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b(?:\\s+(\\d{4}))?");
    private static final Pattern YEAR_ONLY = Pattern.compile("\\b(?:in|during|for|of)\\s+(20\\d{2})\\b");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*(?:%|percent|per\\s+cent)");
    private static final Pattern CONVERT_TO = Pattern.compile("(?i)\\b(?:convert(?:ed)?|combined|in total|totals?)\\s+(?:all\\s+)?(?:(?:the\\s+)?(?:amounts?|figures?|currencies|it)\\s+)?(?:to|into|in)\\s+([a-z]{3})\\b");
    private static final Pattern CURRENCY_UPPER = Pattern.compile("\\b(?:in|currency)\\s+([A-Z]{3})\\b");

    private QuestionRouter() {
    }

    static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw com.finora.finance.core.Validate.invalid("question is required");
        }
        String q = raw.trim();
        if (q.length() < 3 || q.length() > MAX_QUESTION || q.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw com.finora.finance.core.Validate.invalid("question must be 3-" + MAX_QUESTION + " characters without control characters");
        }
        return q.replaceAll("\\s+", " ");
    }

    static boolean sensitive(String question) {
        return SENSITIVE.matcher(question).find();
    }

    static Routed route(InsightsContext ctx, String original) {
        String q = original.toLowerCase(Locale.ROOT);
        if (SENSITIVE.matcher(q).find()) {
            return new Routed(Kind.SENSITIVE, null, "I can only answer read-only questions about this workspace's recorded finance data. I cannot access credentials, other people's data, or change anything.");
        }
        Map<String, String> args = new LinkedHashMap<>();
        Matcher conv = CONVERT_TO.matcher(original);
        if (conv.find() && Currencies.supported(conv.group(1).toUpperCase(Locale.ROOT))) {
            args.put("reporting_currency", conv.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher cur = args.containsKey("reporting_currency") ? CURRENCY_UPPER.matcher("") : CURRENCY_UPPER.matcher(original);
        if (cur.find()) {
            if (!Currencies.supported(cur.group(1))) {
                return Routed.clarify(cur.group(1) + " is not a supported currency. Supported: " + Currencies.codes() + ".");
            }
            args.put("currency", cur.group(1));
        } else if (!args.containsKey("reporting_currency")) {
            for (String code : Currencies.codes()) {
                if (Pattern.compile("\\b" + code.toLowerCase(Locale.ROOT) + "\\b").matcher(q).find()) {
                    args.put("currency", code);
                    break;
                }
            }
        }
        LocalDate today = ctx.today();
        boolean what = q.contains("what if") || q.contains("what-if") || q.contains("suppose") || q.contains("scenario") || q.contains("hypothetical");
        if (what) {
            return whatIf(q, args);
        }
        Routed result;
        if (has(q, "tally", "quickbooks", "zoho", "xero", "accounting software", "accounting system", "accounting integration", "sync with", "sync to", "bookkeeping software")) {
            return new Routed(Kind.UNSUPPORTED, null, "No accounting provider is approved or configured, so nothing can be synchronised or sent to an accounting system, and I cannot do that. "
                    + "I can answer read-only questions about your recorded finance data.");
        }
        if (has(q, "gst", "cgst", "sgst", "igst", "gstr")) {
            if (!ctx.business()) {
                return new Routed(Kind.UNSUPPORTED, null, "GST support exists only in business workspaces.");
            }
            return withPeriod("gst_summary", q, today, ctx.fiscalStartMonth(), new LinkedHashMap<>());
        }
        if (has(q, "unusual", "anomal", "suspicious", "outlier", "strange", "odd transaction", "spike", "duplicate")) {
            result = withPeriod("anomalies", q, today, ctx.fiscalStartMonth(), args);
        } else if (has(q, "forecast", "predict", "projection", "expect to", "next \\d+ months", "next month", "next few months", "going to (spend|earn|have)", "will i (spend|have|earn)")) {
            Matcher m = Pattern.compile("next (\\d+) months?").matcher(q);
            if (m.find()) {
                args.put("horizon_months", m.group(1));
            }
            if (has(q, "income|revenue|earn|receive")) {
                args.put("metric", ctx.business() ? "received" : "income");
            } else if (has(q, "spend|expense|cost")) {
                args.put("metric", ctx.business() ? "expenses" : "net_expense");
            }
            args.remove("currency");
            result = Routed.of("forecast", args);
        } else if (has(q, "overdue", "late payment", "unpaid invoice", "past due")) {
            if (!ctx.business()) {
                return new Routed(Kind.UNSUPPORTED, null, "Invoices exist only in business workspaces. Ask about spending, income or cash flow instead.");
            }
            result = Routed.of("overdue_invoices", new LinkedHashMap<>());
        } else if (has(q, "profit", "margin", "profitab") && has(q, "project")) {
            if (!ctx.business()) {
                return new Routed(Kind.UNSUPPORTED, null, "Projects exist only in business workspaces.");
            }
            args.put("order", has(q, "highest", "best", "most profitable", "top") ? "highest" : "lowest");
            Matcher m = Pattern.compile("(?:top|lowest|highest|worst|best|bottom)\\s+(\\d{1,2})").matcher(q);
            if (m.find()) {
                args.put("limit", m.group(1));
            }
            result = withPeriod("project_profitability", q, today, ctx.fiscalStartMonth(), args);
        } else if (has(q, "why", "explain", "reason", "what changed", "what caused", "compared (to|with)", "vs\\.? (the )?previous", "rose", "increase", "went up", "higher") && has(q, "expense", "spend", "cost", "outgoing")) {
            result = withPeriod("expense_change", q, today, ctx.fiscalStartMonth(), args);
        } else if (has(q, "cash\\s?flow") && has(q, "change", "trend", "changed", "over the last", "past \\d+ months", "last \\d+ months")) {
            Matcher m = LAST_N.matcher(q);
            if (m.find() && m.group(2).equals("month")) {
                args.put("months", m.group(1));
            }
            result = Routed.of("cash_flow_trend", args);
        } else if (has(q, "top", "biggest", "largest", "most") && has(q, "categor", "spend", "expense", "cost")) {
            Matcher m = Pattern.compile("top\\s+(\\d{1,2})").matcher(q);
            if (m.find()) {
                args.put("limit", m.group(1));
            }
            result = withPeriod("top_categories", q, today, ctx.fiscalStartMonth(), args);
        } else if (has(q, "spend", "spent", "spending", "expense", "cost", "income", "earn", "revenue", "received", "invoiced", "cash\\s?flow", "net ")) {
            result = withPeriod("period_summary", q, today, ctx.fiscalStartMonth(), args);
        } else {
            return new Routed(Kind.UNKNOWN, null, null);
        }
        return result;
    }

    private static boolean has(String q, String... patterns) {
        for (String p : patterns) {
            if (Pattern.compile("\\b(?:" + (p.contains("\\") || p.contains("|") || p.contains("(") ? p : Pattern.quote(p)) + ")").matcher(q).find()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- what-if

    private static Routed whatIf(String q, Map<String, String> args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String clause : q.split("\\band\\b|,|;|\\bbut\\b")) {
            Matcher p = PERCENT.matcher(clause);
            if (!p.find()) {
                continue;
            }
            boolean revenue = has(clause, "revenue", "income", "sales", "receipts", "earnings", "receive");
            boolean expense = has(clause, "expense", "spending", "spend", "cost", "outgoing");
            if (revenue == expense) {
                return Routed.clarify("Say whether the " + p.group(1) + "% applies to expenses or to revenue/income, for example \"expenses increase by " + p.group(1) + "%\".");
            }
            boolean down = has(clause, "decreas", "reduc", "cut", "drop", "fall", "fell", "lower", "less", "declin", "shrink", "down");
            boolean up = has(clause, "increas", "rise", "rose", "grow", "higher", "more", "up", "jump", "climb");
            if (down == up) {
                return Routed.clarify("Say whether the " + p.group(1) + "% is an increase or a decrease, for example \"revenue drops by " + p.group(1) + "%\".");
            }
            BigDecimal v = new BigDecimal(p.group(1));
            out.put(revenue ? "revenue_change_percent" : "expense_change_percent", (down ? v.negate() : v).toPlainString());
        }
        Matcher m = Pattern.compile("next (\\d+) months?").matcher(q);
        if (m.find()) {
            out.put("horizon_months", m.group(1));
        }
        if (!out.containsKey("revenue_change_percent") && !out.containsKey("expense_change_percent")) {
            return Routed.clarify("Tell me the assumption in numbers, for example \"What if my expenses increase by 10%?\" or \"What if revenue drops by 20% and costs rise 5%?\".");
        }
        return Routed.of("what_if", out);
    }

    // ---------------------------------------------------------------- periods

    private static Routed withPeriod(String tool, String q, LocalDate today, int fiscalStart, Map<String, String> args) {
        Matcher dates = DATES.matcher(q);
        java.util.List<String> found = new java.util.ArrayList<>();
        while (dates.find()) {
            found.add(dates.group(1));
        }
        if (found.size() == 2) {
            args.put("from", found.get(0));
            args.put("to", found.get(1));
        } else if (found.size() == 1) {
            return Routed.clarify("I found one date. Give both a start and an end date like \"from 2026-01-01 to 2026-01-31\", or a phrase such as \"last month\".");
        } else if (found.size() > 2) {
            return Routed.clarify("I found more than two dates. Give one start and one end date.");
        } else {
            LocalDate[] r = phrase(q, today, fiscalStart);
            if (r != null && r.length == 0) {
                return Routed.clarify("I could not tell which period you mean. Try \"last month\", \"this year\", \"last 3 months\" or \"from 2026-01-01 to 2026-01-31\".");
            }
            if (r != null) {
                args.put("from", r[0].toString());
                args.put("to", r[1].toString());
            }
        }
        return Routed.of(tool, args);
    }

    /** The resolved period, null when none was mentioned (the tool then uses the last complete month), or an empty array when a month name could not be placed. */
    static LocalDate[] phrase(String q, LocalDate today, int fiscalStart) {
        YearMonth thisMonth = YearMonth.from(today);
        if (q.contains("fiscal") || q.contains("financial year")) {
            var presets = com.finora.finance.regional.FiscalPeriods.presets(today, fiscalStart);
            String key = q.contains("last quarter") || q.contains("previous quarter") || (q.contains("quarter") && (q.contains("last") || q.contains("previous"))) ? "last_fiscal_quarter"
                    : q.contains("quarter") ? "this_fiscal_quarter" : (q.contains("last") || q.contains("previous")) ? "last_fiscal_year" : "this_fiscal_year";
            var range = presets.get(key);
            return new LocalDate[] {range.from(), range.to()};
        }
        if (q.contains("last month") || q.contains("previous month")) {
            YearMonth m = thisMonth.minusMonths(1);
            return new LocalDate[] {m.atDay(1), m.atEndOfMonth()};
        }
        if (q.contains("this month")) {
            return new LocalDate[] {thisMonth.atDay(1), today};
        }
        if (q.contains("last quarter") || q.contains("previous quarter")) {
            LocalDate start = today.with(IsoFields.DAY_OF_QUARTER, 1).minusMonths(3);
            return new LocalDate[] {start, start.plusMonths(3).minusDays(1)};
        }
        if (q.contains("this quarter")) {
            return new LocalDate[] {today.with(IsoFields.DAY_OF_QUARTER, 1), today};
        }
        if (q.contains("last year") || q.contains("previous year")) {
            return new LocalDate[] {LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31)};
        }
        if (q.contains("this year") || q.contains("year to date") || q.contains("ytd")) {
            return new LocalDate[] {LocalDate.of(today.getYear(), 1, 1), today};
        }
        if (q.contains("last week") || q.contains("previous week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return new LocalDate[] {start, start.plusDays(6)};
        }
        if (q.contains("this week")) {
            return new LocalDate[] {today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today};
        }
        if (q.contains("today")) {
            return new LocalDate[] {today, today};
        }
        if (q.contains("yesterday")) {
            return new LocalDate[] {today.minusDays(1), today.minusDays(1)};
        }
        Matcher n = LAST_N.matcher(q);
        if (n.find()) {
            int count = Integer.parseInt(n.group(1));
            return switch (n.group(2)) {
                case "day" -> count < 1 || count > 366 ? new LocalDate[0] : new LocalDate[] {today.minusDays(count - 1L), today};
                case "week" -> count < 1 || count > 52 ? new LocalDate[0] : new LocalDate[] {today.minusWeeks(count).plusDays(1), today};
                case "month" -> count < 1 || count > 24 ? new LocalDate[0] : new LocalDate[] {thisMonth.minusMonths(count).atDay(1), thisMonth.minusMonths(1).atEndOfMonth()};
                default -> count < 1 || count > 10 ? new LocalDate[0] : new LocalDate[] {today.minusYears(count).plusDays(1), today};
            };
        }
        Matcher my = MONTH_YEAR.matcher(q);
        if (my.find()) {
            Month month = month(my.group(1));
            int year = my.group(2) == null ? (month.getValue() <= today.getMonthValue() ? today.getYear() : today.getYear() - 1) : Integer.parseInt(my.group(2));
            YearMonth m = YearMonth.of(year, month);
            if (m.isAfter(thisMonth)) {
                return new LocalDate[0];
            }
            return new LocalDate[] {m.atDay(1), m.equals(thisMonth) ? today : m.atEndOfMonth()};
        }
        Matcher yr = YEAR_ONLY.matcher(q);
        if (yr.find()) {
            int year = Integer.parseInt(yr.group(1));
            if (year > today.getYear()) {
                return new LocalDate[0];
            }
            return new LocalDate[] {LocalDate.of(year, 1, 1), year == today.getYear() ? today : LocalDate.of(year, 12, 31)};
        }
        return null;
    }

    private static Month month(String name) {
        for (Month m : Month.values()) {
            String full = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (full.equals(name) || full.startsWith(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("month");
    }
}
