package com.finora.finance.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static BigDecimal parse(String raw, String currency, Amounts.Sign sign) {
        return Amounts.parse("amount", raw, currency, sign);
    }

    @Test
    void decimalsAreExactAndNeverGoThroughFloatingPoint() {
        BigDecimal a = parse("0.10", "INR", Amounts.Sign.POSITIVE);
        BigDecimal b = parse("0.20", "INR", Amounts.Sign.POSITIVE);
        assertThat(a.add(b)).isEqualByComparingTo("0.30");
        assertThat(a.add(b).toPlainString()).isEqualTo("0.30");
        assertThat(0.1 + 0.2).as("the trap this code avoids").isNotEqualTo(0.3);
        assertThat(parse("999999999999.99", "INR", Amounts.Sign.POSITIVE).toPlainString()).isEqualTo("999999999999.99");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 1000; i++) {
            sum = sum.add(parse("0.01", "USD", Amounts.Sign.POSITIVE));
        }
        assertThat(sum.toPlainString()).isEqualTo("10.00");
    }

    @Test
    void precisionFollowsTheCurrency() {
        assertThat(parse("1500", "JPY", Amounts.Sign.POSITIVE).toPlainString()).isEqualTo("1500");
        assertThat(parse("1.234", "KWD", Amounts.Sign.POSITIVE).toPlainString()).isEqualTo("1.234");
        assertThat(parse("12", "INR", Amounts.Sign.POSITIVE).toPlainString()).isEqualTo("12.00");
        assertThatThrownBy(() -> parse("10.5", "JPY", Amounts.Sign.POSITIVE)).isInstanceOf(ApiException.class).hasMessageContaining("at most 0");
        assertThatThrownBy(() -> parse("1.234", "INR", Amounts.Sign.POSITIVE)).hasMessageContaining("at most 2");
        assertThatThrownBy(() -> parse("1.2340", "KWD", Amounts.Sign.POSITIVE)).hasMessageContaining("too many decimals");
        assertThat(Currencies.format(new BigDecimal("5"), "INR")).isEqualTo("5.00");
        assertThat(Currencies.format(new BigDecimal("5"), "JPY")).isEqualTo("5");
        assertThat(Currencies.format(new BigDecimal("0.5"), "KWD")).isEqualTo("0.500");
    }

    @Test
    void malformedAndOutOfRangeInputIsRefusedNotRounded() {
        for (String bad : new String[] {"", " ", "abc", "1e3", "1,000.00", "--5", "1.", ".5", "NaN", "Infinity", "1000000000000000", "0x10", null}) {
            assertThatThrownBy(() -> parse(bad, "INR", Amounts.Sign.ANY)).as(String.valueOf(bad)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> parse("1000000000000", "INR", Amounts.Sign.POSITIVE)).hasMessageContaining("too large");
        assertThatThrownBy(() -> parse("1", "XXX", Amounts.Sign.POSITIVE)).hasMessageContaining("not supported");
    }

    @Test
    void signRulesAreEnforced() {
        assertThatThrownBy(() -> parse("0", "INR", Amounts.Sign.POSITIVE)).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> parse("-1", "INR", Amounts.Sign.POSITIVE)).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> parse("0.00", "INR", Amounts.Sign.NON_ZERO)).hasMessageContaining("must not be zero");
        assertThat(parse("-5.50", "INR", Amounts.Sign.NON_ZERO)).isEqualByComparingTo("-5.50");
        assertThat(parse("-5.50", "INR", Amounts.Sign.ANY)).isEqualByComparingTo("-5.50");
    }

    @Test
    void everyListedCurrencyHasAValidExponent() {
        for (String code : Currencies.codes()) {
            assertThat(Currencies.exponentOrThrow(code)).isBetween(0, 3);
        }
        assertThat(Currencies.supported("inr")).as("codes are upper-case ISO 4217").isFalse();
    }
}
