package com.finora.finance.insights;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The shape shared by every report, so the web table and the CSV export are generated from the same data. */
public final class ReportModel {

    private ReportModel() {
    }

    public record Row(String period, LocalDate start, LocalDate end, boolean activity, Map<String, String> values) {
    }

    public record CurrencyReport(String currency, List<String> columns, List<Row> rows, Map<String, String> totals) {
    }

    public record Delta(String amount, String percent) {
    }

    public record CurrencyComparison(String currency, Map<String, String> current, Map<String, String> previous, Map<String, Delta> change) {
    }

    public record Comparison(LocalDate from, LocalDate to, List<CurrencyComparison> currencies) {
    }

    public record Report(String report, String title, String domain, LocalDate from, LocalDate to, String granularity, List<String> definitions,
            List<String> notes, List<CurrencyReport> currencies, Comparison comparison) {
    }
}
