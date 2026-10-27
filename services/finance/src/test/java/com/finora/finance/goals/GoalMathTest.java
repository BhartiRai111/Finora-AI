package com.finora.finance.goals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GoalMathTest {

    @Test
    void monthsLeftRoundsUpToWholeMonths() {
        LocalDate today = LocalDate.parse("2026-03-15");
        assertThat(GoalService.monthsLeft(today, LocalDate.parse("2026-04-15"))).isEqualTo(1);
        assertThat(GoalService.monthsLeft(today, LocalDate.parse("2026-04-16"))).isEqualTo(2);
        assertThat(GoalService.monthsLeft(today, LocalDate.parse("2026-03-16"))).as("never less than one month").isEqualTo(1);
        assertThat(GoalService.monthsLeft(today, LocalDate.parse("2027-03-15"))).isEqualTo(12);
        assertThat(GoalService.monthsLeft(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"))).isEqualTo(1);
    }
}
