package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.business.InvoiceMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InvoiceMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void lineAmountsAreExactAndRoundedOnceHalfUpToTheCurrencyPrecision() {
        // 3 x 33.33 = 99.99; less 10.00 = 89.99; 18% = 16.1982 -> 16.20
        InvoiceMath.Line l = InvoiceMath.line(d("3"), d("33.33"), d("10.00"), d("18"), 2);
        assertThat(l.gross()).isEqualByComparingTo("99.99");
        assertThat(l.net()).isEqualByComparingTo("89.99");
        assertThat(l.tax()).isEqualByComparingTo("16.20");
        assertThat(l.total()).isEqualByComparingTo("106.19");
        assertThat(l.total().scale()).isEqualTo(2);
    }

    @Test
    void halfwayCasesRoundUpAndNoBinaryFloatingPointCreepsIn() {
        assertThat(InvoiceMath.line(d("0.5"), d("1.01"), d("0.00"), d("0"), 2).gross()).isEqualByComparingTo("0.51");
        // 0.1 + 0.2 style traps: 3 x 0.10 must be exactly 0.30 and 1.15 x 100 exactly 115.00
        assertThat(InvoiceMath.line(d("3"), d("0.10"), d("0.00"), d("0"), 2).total()).isEqualByComparingTo("0.30");
        assertThat(InvoiceMath.line(d("100"), d("1.15"), d("0.00"), d("0"), 2).total()).isEqualByComparingTo("115.00");
    }

    @Test
    void zeroDecimalAndThreeDecimalCurrenciesUseTheirOwnPrecision() {
        InvoiceMath.Line jpy = InvoiceMath.line(d("1.5"), d("101"), d("0"), d("5"), 0);
        assertThat(jpy.gross()).isEqualByComparingTo("152"); // 151.5 -> 152
        assertThat(jpy.tax()).isEqualByComparingTo("8");     // 152 x 5% = 7.6 -> 8
        InvoiceMath.Line kwd = InvoiceMath.line(d("1"), d("1.005"), d("0.005"), d("10"), 3);
        assertThat(kwd.net()).isEqualByComparingTo("1.000");
        assertThat(kwd.tax()).isEqualByComparingTo("0.100");
    }
}
