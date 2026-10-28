import { describe, expect, it } from "vitest";
import { compareAmounts, isNegative, isZero, percentOf, subtractAmounts, sumAmounts } from "./decimal";

describe("exact decimal helpers", () => {
  it("adds without floating-point error", () => {
    expect(sumAmounts(["0.1", "0.2"])).toBe("0.3");
    expect(sumAmounts(["1000.10", "2000.20", "0.05"])).toBe("3000.35");
    expect(sumAmounts(["12345678901234567890.12", "0.88"])).toBe("12345678901234567891.00");
    expect(sumAmounts(["-5.00", "2.50"])).toBe("-2.50");
    expect(sumAmounts([])).toBe("0");
    expect(sumAmounts(["abc", "1.5"])).toBe("1.5");
  });

  it("subtracts and compares exactly", () => {
    expect(subtractAmounts("100.00", "99.99")).toBe("0.01");
    expect(subtractAmounts("1.5", "2.25")).toBe("-0.75");
    expect(compareAmounts("1.10", "1.1")).toBe(0);
    expect(compareAmounts("-1", "0.5")).toBe(-1);
    expect(compareAmounts("2", "1.999")).toBe(1);
    expect(isZero("0.00")).toBe(true);
    expect(isNegative("-0.01")).toBe(true);
    expect(isNegative("0.00")).toBe(false);
  });

  it("gives whole-number shares for labels, or null when there is no whole", () => {
    expect(percentOf("25.00", "100.00")).toBe(25);
    expect(percentOf("1", "3")).toBe(33);
    expect(percentOf("2", "3")).toBe(67);
    expect(percentOf("150", "100")).toBe(150);
    expect(percentOf("1", "0")).toBeNull();
    expect(percentOf("x", "1")).toBeNull();
  });
});
