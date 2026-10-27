package com.finora.finance.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.assistant.AnomalyDetector.Finding;
import com.finora.finance.assistant.AssistantQueries.Rec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The anomaly rules on plain data: each one fires when its documented condition holds, stays quiet otherwise, and explains itself with exact figures. */
class AnomalyDetectorTest {

    private static final LocalDate FROM = LocalDate.parse("2026-02-01");
    private static final LocalDate TO = LocalDate.parse("2026-02-28");

    private static Rec rec(String date, String amount, String category, String payee, String currency) {
        return new Rec(UUID.randomUUID(), LocalDate.parse(date), currency, category, payee, new BigDecimal(amount));
    }

    private static List<Rec> baseline(String category, String currency, String... amounts) {
        List<Rec> out = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            out.add(rec(LocalDate.parse("2025-09-10").plusDays(i * 12L).toString(), amounts[i], category, null, currency));
        }
        return out;
    }

    @Test
    void aRecordFarAboveTheCategoryBaselineIsFlaggedWithItsReason() {
        List<Rec> base = baseline("Dining", "INR", "100.00", "110.00", "120.00", "105.00", "95.00", "115.00", "100.00", "110.00");
        Rec big = rec("2026-02-10", "1000.00", "Dining", null, "INR");
        List<Finding> f = AnomalyDetector.detect(List.of(big), base, FROM, TO);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).rule()).isEqualTo("LARGE_AMOUNT");
        assertThat(f.get(0).amount()).isEqualTo("1000.00");
        assertThat(f.get(0).reason()).startsWith("Potential anomaly:").contains("this amount, 1000.00, is 9.3 times the usual (median 107.50)").contains("8 earlier records").contains("INR")
                .doesNotContainIgnoringCase("fraud");
    }

    @Test
    void ordinaryVariationIsNotFlagged() {
        List<Rec> base = baseline("Dining", "INR", "100.00", "110.00", "120.00", "105.00", "95.00", "115.00", "100.00", "110.00");
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "140.00", "Dining", null, "INR")), base, FROM, TO)).isEmpty();
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "180.00", "Dining", null, "INR")), base, FROM, TO)).as("above 2x median but within 3 spreads of a noisy baseline").isNotNull();
    }

    @Test
    void sparseHistoryCannotProduceAnAnomalyAndTheBaselineIsPerCategoryAndCurrency() {
        Rec big = rec("2026-02-10", "5000.00", "Dining", null, "INR");
        assertThat(AnomalyDetector.detect(List.of(big), baseline("Dining", "INR", "100.00", "100.00", "100.00", "100.00", "100.00"), FROM, TO)).as("5 earlier records are not enough").isEmpty();
        List<Rec> other = baseline("Travel", "INR", "100.00", "100.00", "100.00", "100.00", "100.00", "100.00", "100.00");
        assertThat(AnomalyDetector.detect(List.of(big), other, FROM, TO)).as("another category is no baseline").isEmpty();
        List<Rec> usd = baseline("Dining", "USD", "100.00", "100.00", "100.00", "100.00", "100.00", "100.00", "100.00");
        assertThat(AnomalyDetector.detect(List.of(big), usd, FROM, TO)).as("another currency is no baseline, and currencies are never mixed").isEmpty();
    }

    @Test
    void aPerfectlyRegularBaselineFlagsADoublingButNotAnythingSmaller() {
        List<Rec> base = baseline("Rent", "INR", "100.00", "100.00", "100.00", "100.00", "100.00", "100.00");
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "250.00", "Rent", null, "INR")), base, FROM, TO)).hasSize(1);
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "150.00", "Rent", null, "INR")), base, FROM, TO)).as("above 3 spreads (0) but below 2x the median").isEmpty();
    }

    @Test
    void recordsOutsideTheSixMonthBaselineAreIgnored() {
        List<Rec> old = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            old.add(rec("2025-06-0" + (i + 1), "100.00", "Dining", null, "INR"));
        }
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "1000.00", "Dining", null, "INR")), old, FROM, TO)).isEmpty();
    }

    @Test
    void duplicatesNeedTheSamePayeeAmountCurrencyWithinThreeDays() {
        Rec a = rec("2026-02-10", "499.00", "Software", "Acme Cloud", "INR");
        Rec b = rec("2026-02-12", "499.00", "Software", "ACME cloud", "INR");
        List<Finding> f = AnomalyDetector.detect(List.of(a, b), List.of(), FROM, TO);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).rule()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(f.get(0).recordId()).isEqualTo(b.id());
        assertThat(f.get(0).reason()).contains("2026-02-10").contains("2 day(s) apart");
        assertThat(AnomalyDetector.detect(List.of(a, rec("2026-02-14", "499.00", "Software", "Acme Cloud", "INR")), List.of(), FROM, TO)).as("4 days apart").isEmpty();
        assertThat(AnomalyDetector.detect(List.of(a, rec("2026-02-11", "499.00", "Software", "Acme Cloud", "USD")), List.of(), FROM, TO)).as("other currency").isEmpty();
        assertThat(AnomalyDetector.detect(List.of(a, rec("2026-02-11", "499.01", "Software", "Acme Cloud", "INR")), List.of(), FROM, TO)).as("other amount").isEmpty();
        assertThat(AnomalyDetector.detect(List.of(rec("2026-02-10", "5.00", "Food", null, "INR"), rec("2026-02-10", "5.00", "Food", null, "INR")), List.of(), FROM, TO)).as("no payee, no check").isEmpty();
    }

    @Test
    void aCategoryTotalFarAboveThreeEarlierEqualPeriodsIsASpike() {
        List<Rec> base = new ArrayList<>(List.of(rec("2026-01-20", "100.00", "Fuel", null, "INR"), rec("2026-01-10", "110.00", "Fuel", null, "INR"), rec("2026-01-04", "90.00", "Fuel", null, "INR")));
        // the period is 2026-02-01..2026-02-28 (28 days): the three earlier 28-day periods end 2026-01-31, 2026-01-03 and 2025-12-06
        base.clear();
        base.add(rec("2026-01-15", "100.00", "Fuel", null, "INR"));
        base.add(rec("2025-12-20", "110.00", "Fuel", null, "INR"));
        base.add(rec("2025-11-25", "90.00", "Fuel", null, "INR"));
        List<Finding> spike = AnomalyDetector.categorySpikes(List.of(rec("2026-02-05", "400.00", "Fuel", null, "INR")), base, FROM, TO);
        assertThat(spike).hasSize(1);
        assertThat(spike.get(0).rule()).isEqualTo("CATEGORY_SPIKE");
        assertThat(spike.get(0).reason()).contains("400.00").contains("4.0 times the average of the 3 previous 28-day periods (100.00");
        assertThat(AnomalyDetector.categorySpikes(List.of(rec("2026-02-05", "130.00", "Fuel", null, "INR")), base, FROM, TO)).as("within normal variation").isEmpty();
        assertThat(AnomalyDetector.categorySpikes(List.of(rec("2026-02-05", "400.00", "Fuel", null, "INR")), base.subList(0, 2), FROM, TO)).as("only two earlier periods").isEmpty();
        assertThat(AnomalyDetector.categorySpikes(List.of(rec("2026-02-05", "400.00", "Fuel", null, "INR")), base, FROM, FROM.plusDays(5))).as("a period under 7 days").isEmpty();
    }

    @Test
    void amountsAreExactDecimalsInTheReason() {
        List<Rec> base = baseline("Dining", "INR", "0.10", "0.20", "0.10", "0.20", "0.10", "0.20", "0.10");
        List<Finding> f = AnomalyDetector.detect(List.of(rec("2026-02-10", "0.90", "Dining", null, "INR")), base, FROM, TO);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).amount()).isEqualTo("0.90");
        assertThat(f.get(0).reason()).contains("0.90").doesNotContain("E-").doesNotContain("0.8999");
    }
}
