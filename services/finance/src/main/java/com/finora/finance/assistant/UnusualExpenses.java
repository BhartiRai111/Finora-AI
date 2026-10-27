package com.finora.finance.assistant;

import com.finora.finance.assistant.AssistantQueries.Rec;
import com.finora.finance.insights.InsightsContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A narrow public door to the deterministic record-level anomaly rules for the alert engine (same rules, same limits as the assistant). */
@Component
public class UnusualExpenses {

    public record Finding(String rule, UUID recordId, LocalDate date, String currency, String amount) {
    }

    private final AssistantQueries queries;

    UnusualExpenses(AssistantQueries queries) {
        this.queries = queries;
    }

    /** LARGE_AMOUNT and POSSIBLE_DUPLICATE findings for expense records dated within [from, to]. Needs full report access (the caller checks). */
    public List<Finding> detect(InsightsContext ctx, LocalDate from, LocalDate to) {
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        List<Rec> all = queries.expenseRecords(ctx, from.minusDays(Math.max(183, len * 3)), to);
        List<Rec> window = all.stream().filter(x -> !x.onDate().isBefore(from)).toList();
        List<Rec> baseline = all.stream().filter(x -> x.onDate().isBefore(from)).toList();
        return java.util.stream.Stream.concat(AnomalyDetector.largeAmounts(window, baseline, from).stream(), AnomalyDetector.duplicates(window).stream())
                .map(f -> new Finding(f.rule(), f.recordId(), f.date(), f.currency(), f.amount())).toList();
    }
}
