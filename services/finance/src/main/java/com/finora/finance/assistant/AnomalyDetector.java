package com.finora.finance.assistant;

import com.finora.finance.assistant.AssistantQueries.Rec;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Explainable, deterministic rules for POTENTIAL anomalies in expense records. Each finding carries the rule and a plain-language reason with
 * the figures it was based on. There is no model in here, and the rules say nothing about fraud or error: a high amount can be a legitimate
 * one-off. Everything is exact decimal arithmetic.
 *
 * <ul>
 *   <li>LARGE_AMOUNT: a record above the average plus 3 population standard deviations AND at least twice the median of at least 6 earlier
 *       records (up to 6 months back) in the same category and currency.</li>
 *   <li>CATEGORY_SPIKE: for periods of at least 7 days, a category total above the average plus 2 standard deviations AND at least 1.5 times the
 *       average of the 3 previous equal-length periods, when all 3 had spending.</li>
 *   <li>POSSIBLE_DUPLICATE: the same payee or vendor, amount and currency within 3 days of each other.</li>
 * </ul>
 */
final class AnomalyDetector {

    static final int MIN_BASELINE_RECORDS = 6;
    static final int MAX_FINDINGS = 20;
    static final int DUPLICATE_DAYS = 3;
    static final int MIN_PERIOD_DAYS = 7;

    record Finding(String rule, UUID recordId, LocalDate date, String currency, String amount, String category, String counterparty, String reason) {
    }

    private AnomalyDetector() {
    }

    static List<Finding> detect(List<Rec> window, List<Rec> baseline, LocalDate from, LocalDate to) {
        List<Finding> out = new ArrayList<>();
        out.addAll(largeAmounts(window, baseline, from));
        out.addAll(categorySpikes(window, baseline, from, to));
        out.addAll(duplicates(window));
        out.sort(Comparator.comparing((Finding f) -> f.date() == null ? LocalDate.MIN : f.date()).reversed().thenComparing(Finding::rule).thenComparing(f -> String.valueOf(f.recordId())));
        return out;
    }

    private static String key(String currency, String category) {
        return currency.trim() + "|" + category;
    }

    private static String fmt(BigDecimal v, String currency) {
        String cur = currency.trim();
        return Currencies.format(v.setScale(Currencies.exponentOrThrow(cur), RoundingMode.HALF_EVEN), cur);
    }

    private static BigDecimal mean(List<BigDecimal> v, int scale) {
        return v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(v.size()), scale, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal sd(List<BigDecimal> v, BigDecimal mean) {
        BigDecimal variance = v.stream().map(x -> x.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(v.size()), 12, RoundingMode.HALF_EVEN);
        return variance.sqrt(new MathContext(20, RoundingMode.HALF_EVEN));
    }

    private static BigDecimal median(List<BigDecimal> v, int scale) {
        List<BigDecimal> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : s.get(n / 2 - 1).add(s.get(n / 2)).divide(BigDecimal.valueOf(2), scale, RoundingMode.HALF_EVEN);
    }

    static List<Finding> largeAmounts(List<Rec> window, List<Rec> baseline, LocalDate from) {
        LocalDate earliest = from.minusMonths(6);
        Map<String, List<Rec>> byKey = new HashMap<>();
        for (Rec r : baseline) {
            if (r.onDate().isBefore(from) && !r.onDate().isBefore(earliest)) {
                byKey.computeIfAbsent(key(r.currency(), r.category()), k -> new ArrayList<>()).add(r);
            }
        }
        List<Finding> out = new ArrayList<>();
        for (Rec r : window) {
            List<Rec> base = byKey.getOrDefault(key(r.currency(), r.category()), List.of());
            if (base.size() < MIN_BASELINE_RECORDS) {
                continue;
            }
            String cur = r.currency().trim();
            int exp = Currencies.exponentOrThrow(cur);
            List<BigDecimal> amounts = base.stream().map(Rec::amount).toList();
            BigDecimal mean = mean(amounts, exp + 6);
            BigDecimal sd = sd(amounts, mean);
            BigDecimal median = median(amounts, exp + 6);
            BigDecimal threshold = mean.add(sd.multiply(BigDecimal.valueOf(3)));
            if (r.amount().compareTo(threshold) > 0 && r.amount().compareTo(median.multiply(BigDecimal.valueOf(2))) >= 0) {
                LocalDate first = base.stream().map(Rec::onDate).min(LocalDate::compareTo).orElseThrow();
                LocalDate last = base.stream().map(Rec::onDate).max(LocalDate::compareTo).orElseThrow();
                String times = r.amount().divide(median, 1, RoundingMode.HALF_EVEN).toPlainString();
                out.add(new Finding("LARGE_AMOUNT", r.id(), r.onDate(), cur, fmt(r.amount(), cur), r.category(), r.counterparty(),
                        "Potential anomaly: this amount, " + fmt(r.amount(), cur) + ", is " + times + " times the usual (median " + fmt(median, cur) + ") for this category in " + cur + ", based on "
                                + base.size() + " earlier records from " + first + " to " + last + ". Their average is " + fmt(mean, cur) + " with a spread (standard deviation) of "
                                + fmt(sd, cur) + "; this record is above the average plus 3 spreads (" + fmt(threshold, cur) + ")."));
            }
        }
        return out;
    }

    static List<Finding> categorySpikes(List<Rec> window, List<Rec> baseline, LocalDate from, LocalDate to) {
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        if (len < MIN_PERIOD_DAYS) {
            return List.of();
        }
        Map<String, BigDecimal> current = new TreeMap<>();
        Map<String, String> cats = new HashMap<>();
        for (Rec r : window) {
            current.merge(key(r.currency(), r.category()), r.amount(), BigDecimal::add);
            cats.put(key(r.currency(), r.category()), r.category());
        }
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : current.entrySet()) {
            String cur = e.getKey().substring(0, e.getKey().indexOf('|'));
            List<BigDecimal> previous = new ArrayList<>();
            for (int k = 1; k <= 3; k++) {
                LocalDate pf = from.minusDays(len * k);
                LocalDate pt = from.minusDays(len * (k - 1L) + 1);
                BigDecimal total = baseline.stream().filter(r -> key(r.currency(), r.category()).equals(e.getKey()) && !r.onDate().isBefore(pf) && !r.onDate().isAfter(pt))
                        .map(Rec::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.signum() > 0) {
                    previous.add(total);
                }
            }
            if (previous.size() < 3) {
                continue;
            }
            int exp = Currencies.exponentOrThrow(cur);
            BigDecimal mean = mean(previous, exp + 6);
            BigDecimal sd = sd(previous, mean);
            BigDecimal total = e.getValue();
            if (total.compareTo(mean.add(sd.multiply(BigDecimal.valueOf(2)))) > 0 && total.compareTo(mean.multiply(new BigDecimal("1.5"))) >= 0) {
                String times = total.divide(mean, 1, RoundingMode.HALF_EVEN).toPlainString();
                out.add(new Finding("CATEGORY_SPIKE", null, null, cur, fmt(total, cur), cats.get(e.getKey()), null,
                        "Potential anomaly: spending in this category for " + from + " to " + to + " was " + fmt(total, cur) + ", " + times + " times the average of the 3 previous "
                                + len + "-day periods (" + fmt(mean, cur) + "; spread " + fmt(sd, cur) + "), which each had spending in this category."));
            }
        }
        return out;
    }

    static List<Finding> duplicates(List<Rec> window) {
        List<Rec> sorted = window.stream().filter(r -> r.counterparty() != null && !r.counterparty().isBlank())
                .sorted(Comparator.comparing((Rec r) -> r.currency().trim()).thenComparing(r -> r.counterparty().trim().toLowerCase(Locale.ROOT)).thenComparing(Rec::amount)
                        .thenComparing(Rec::onDate).thenComparing(Rec::id)).toList();
        List<Finding> out = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Rec a = sorted.get(i - 1);
            Rec b = sorted.get(i);
            if (a.currency().trim().equals(b.currency().trim()) && a.counterparty().trim().equalsIgnoreCase(b.counterparty().trim()) && a.amount().compareTo(b.amount()) == 0) {
                long days = ChronoUnit.DAYS.between(a.onDate(), b.onDate());
                if (days <= DUPLICATE_DAYS) {
                    String cur = b.currency().trim();
                    out.add(new Finding("POSSIBLE_DUPLICATE", b.id(), b.onDate(), cur, fmt(b.amount(), cur), b.category(), b.counterparty(),
                            "Potential anomaly: same payee or vendor, same amount and currency as another record dated " + a.onDate() + " (" + days + " day(s) apart). "
                                    + "Recurring bills and repeat purchases can look like this."));
                }
            }
        }
        return out;
    }
}
