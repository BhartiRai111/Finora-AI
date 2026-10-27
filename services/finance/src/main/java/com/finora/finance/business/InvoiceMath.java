package com.finora.finance.business;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Invoice line arithmetic. Every step is exact decimal arithmetic; rounding happens once per amount, to the currency's
 * precision, HALF_UP, at line level. That rounding rule is a WORKING PROPOSAL (PD-09 / EXT-01 are not approved and nothing
 * has been validated by a tax professional): the service makes no GST or tax-compliance claim. The tax percentage is
 * whatever the user typed; no tax rule, rate table or place-of-supply logic exists.
 */
public final class InvoiceMath {

    private InvoiceMath() {
    }

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public record Line(BigDecimal gross, BigDecimal net, BigDecimal tax, BigDecimal total) {
    }

    /**
     * gross = round(quantity x unit price); net = gross - discount; tax = round(net x percent / 100); total = net + tax.
     *
     * @param exponent decimal places of the invoice currency
     */
    public static Line line(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxPercent, int exponent) {
        BigDecimal gross = quantity.multiply(unitPrice).setScale(exponent, ROUNDING);
        BigDecimal net = gross.subtract(discount);
        BigDecimal tax = net.multiply(taxPercent).divide(BigDecimal.valueOf(100), exponent, ROUNDING);
        return new Line(gross, net, tax, net.add(tax));
    }
}
