package com.finora.finance.commitments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DueDateTest {

    private static CommitmentService.Row row(String frequency, String anchor) {
        return new CommitmentService.Row(UUID.randomUUID(), "SUBSCRIPTION", "x", BigDecimal.TEN, "INR", frequency, LocalDate.parse(anchor), 0, "ACTIVE",
                null, null, null, 0);
    }

    @Test
    void dueDatesAreAnchoredAndClamped() {
        assertThat(CommitmentService.due(row("MONTHLY", "2026-01-31"), 1)).isEqualTo("2026-02-28");
        assertThat(CommitmentService.due(row("MONTHLY", "2026-01-31"), 2)).isEqualTo("2026-03-31");
        assertThat(CommitmentService.due(row("QUARTERLY", "2026-01-31"), 1)).isEqualTo("2026-04-30");
        assertThat(CommitmentService.due(row("WEEKLY", "2026-03-02"), 2)).isEqualTo("2026-03-16");
        assertThat(CommitmentService.due(row("YEARLY", "2024-02-29"), 1)).isEqualTo("2025-02-28");
        assertThat(CommitmentService.due(row("ONCE", "2026-05-01"), 0)).isEqualTo("2026-05-01");
    }
}
