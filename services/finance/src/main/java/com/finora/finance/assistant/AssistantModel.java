package com.finora.finance.assistant;

import java.time.LocalDate;
import java.util.List;

/**
 * The shape of every assistant answer. Facts live in {@link Section}s whose {@code kind} says what they are: VERIFIED (plain arithmetic on
 * recorded data), ESTIMATE (the Step 19 baseline forecast), HYPOTHETICAL (a what-if on explicit user assumptions, never saved) or OBSERVATION
 * (a potential anomaly from a documented rule). The optional AI text is separate and always labelled; it never carries a figure.
 */
public final class AssistantModel {

    private AssistantModel() {
    }

    public record Section(String title, String kind, String currency, List<String> columns, List<List<String>> rows, List<String> notes) {
    }

    public record Source(String type, String name, String from, String to, String ref) {
    }

    public record Period(LocalDate from, LocalDate to, String label, LocalDate comparisonFrom, LocalDate comparisonTo, String comparisonLabel) {
    }

    /**
     * @param status NOT_CONFIGURED (no provider chosen), UNAVAILABLE (configured but unreachable), FAILED (rejected key, rate limit, malformed or rejected
     *               output, limit reached), EXECUTED (a provider answered and the answer passed validation), SKIPPED (nothing to explain), BLOCKED (provider not approved for data)
     */
    public record Ai(String status, String reason, String provider, String message, String explanation, String label) {
    }

    public record Answer(String status, String intent, String domain, Period period, String summary, String clarification, List<Section> sections,
            List<String> assumptions, List<String> limitations, List<Source> sources, List<String> notices, Ai ai) {
    }

    /** A section plus what the assistant needs to know to minimise what it sends to a provider; never serialised. */
    public record Built(Section section, int labelColumn, boolean modelVisible) {
    }

    public record ToolResult(String status, String summary, String clarification, Period period, List<Built> sections, List<String> assumptions,
            List<String> limitations, List<Source> sources, int suspiciousLabels) {
    }
}
