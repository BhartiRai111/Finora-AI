package com.finora.finance.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OccurrenceTest {

    private static RecurringService.Rule rule(String frequency, int interval, String start) {
        return new RecurringService.Rule(UUID.randomUUID(), "EXPENSE", UUID.randomUUID(), null, BigDecimal.TEN, "INR", null, null, frequency,
                interval, LocalDate.parse(start), null, 0, false, true, 0);
    }

    @Test
    void monthlyRuleAnchoredOnThe31stClampsToMonthEndAndReturns() {
        RecurringService.Rule r = rule("MONTHLY", 1, "2026-01-31");
        assertThat(RecurringService.occurrence(r, 0)).isEqualTo("2026-01-31");
        assertThat(RecurringService.occurrence(r, 1)).isEqualTo("2026-02-28");
        assertThat(RecurringService.occurrence(r, 2)).as("not drifted to the 28th").isEqualTo("2026-03-31");
        assertThat(RecurringService.occurrence(r, 3)).isEqualTo("2026-04-30");
        assertThat(RecurringService.occurrence(r, 13)).isEqualTo("2027-02-28");
    }

    @Test
    void leapDayYearlyRuleLandsOnFeb28InCommonYears() {
        RecurringService.Rule r = rule("YEARLY", 1, "2024-02-29");
        assertThat(RecurringService.occurrence(r, 1)).isEqualTo("2025-02-28");
        assertThat(RecurringService.occurrence(r, 4)).isEqualTo("2028-02-29");
    }

    @Test
    void intervalsMultiplyTheStep() {
        assertThat(RecurringService.occurrence(rule("WEEKLY", 2, "2026-03-02"), 3)).isEqualTo("2026-04-13");
        assertThat(RecurringService.occurrence(rule("DAILY", 10, "2026-03-01"), 2)).isEqualTo("2026-03-21");
        assertThat(RecurringService.occurrence(rule("MONTHLY", 3, "2026-01-15"), 2)).isEqualTo("2026-07-15");
    }

    @Test
    void anEndedRuleHasNoNextOccurrence() {
        RecurringService.Rule open = rule("MONTHLY", 1, "2026-01-10");
        RecurringService.Rule ended = new RecurringService.Rule(open.id(), open.kind(), open.accountId(), null, open.amount(), "INR", null, null, "MONTHLY", 1,
                open.startDate(), LocalDate.parse("2026-03-10"), 3, false, true, 0);
        assertThat(RecurringService.occurrence(ended, 2)).isEqualTo("2026-03-10");
        assertThat(RecurringService.occurrence(ended, 3)).isEqualTo("2026-04-10");
    }
}
