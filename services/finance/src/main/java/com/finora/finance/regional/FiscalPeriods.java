package com.finora.finance.regional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fiscal-year arithmetic from a start month (1 = January). A fiscal year is named by the calendar year in which it starts. */
public final class FiscalPeriods {

    public record Range(LocalDate from, LocalDate end, LocalDate to) {
    }

    private FiscalPeriods() {
    }

    private static Range range(LocalDate from, LocalDate end, LocalDate today) {
        return new Range(from, end, end.isAfter(today) ? today : end);
    }

    public static LocalDate fiscalYearStart(LocalDate date, int startMonth) {
        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(year, startMonth, 1);
    }

    /** Fiscal year number as shown to people: the year in which it starts. */
    public static Map<String, Range> presets(LocalDate today, int startMonth) {
        LocalDate fy = fiscalYearStart(today, startMonth);
        int quarter = (int) (java.time.temporal.ChronoUnit.MONTHS.between(fy, today.withDayOfMonth(1)) / 3);
        LocalDate q = fy.plusMonths(3L * quarter);
        Map<String, Range> out = new LinkedHashMap<>();
        out.put("this_fiscal_year", range(fy, fy.plusYears(1).minusDays(1), today));
        out.put("last_fiscal_year", range(fy.minusYears(1), fy.minusDays(1), today));
        out.put("this_fiscal_quarter", range(q, q.plusMonths(3).minusDays(1), today));
        out.put("last_fiscal_quarter", range(q.minusMonths(3), q.minusDays(1), today));
        return out;
    }
}
