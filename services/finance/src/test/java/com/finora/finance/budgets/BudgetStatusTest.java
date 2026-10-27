package com.finora.finance.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetStatusTest {

    private static String status(String spent, String budget) {
        return BudgetService.status(new BigDecimal(spent), new BigDecimal(budget));
    }

    @Test
    void boundariesAreExact() {
        assertThat(status("0.00", "1000.00")).isEqualTo("UNDER");
        assertThat(status("799.99", "1000.00")).as("one cent under 80%").isEqualTo("UNDER");
        assertThat(status("800.00", "1000.00")).as("exactly 80%").isEqualTo("WARNING");
        assertThat(status("1000.00", "1000.00")).as("exactly the budget is at the limit, not over").isEqualTo("WARNING");
        assertThat(status("1000.01", "1000.00")).as("one cent over").isEqualTo("OVER");
        assertThat(status("-25.00", "1000.00")).as("refunds can exceed spending").isEqualTo("UNDER");
    }

    @Test
    void worksWithSubUnitCurrencies() {
        assertThat(status("0.800", "1.000")).isEqualTo("WARNING");
        assertThat(status("1.001", "1.000")).isEqualTo("OVER");
        assertThat(status("799", "1000")).isEqualTo("UNDER");
    }
}
