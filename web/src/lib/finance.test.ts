import { describe, expect, it } from "vitest";
import { formatMoney, groupAmount, validateAmountText } from "./finance";

describe("money text handling", () => {
  it("groups digits as text without losing precision", () => {
    expect(groupAmount("12345678901234567.89", "USD")).toBe("12,345,678,901,234,567.89");
    expect(groupAmount("-1234567.50", "INR")).toBe("-12,34,567.50");
    expect(groupAmount("0.10", "INR")).toBe("0.10");
    expect(formatMoney("999", "INR")).toBe("INR 999");
  });

  it("rejects over-precise, negative, zero and malformed amounts", () => {
    expect(validateAmountText("10.001", "USD")).toMatch(/at most 2/);
    expect(validateAmountText("10.5", "JPY")).toMatch(/at most 0/);
    expect(validateAmountText("1.234", "KWD")).toBeNull();
    expect(validateAmountText("0", "USD")).toMatch(/greater than zero/);
    expect(validateAmountText("0", "USD", { allowZero: true })).toBeNull();
    expect(validateAmountText("-5", "USD")).not.toBeNull();
    expect(validateAmountText("-5", "USD", { allowNegative: true })).toBeNull();
    expect(validateAmountText("1e3", "USD")).not.toBeNull();
    expect(validateAmountText("", "USD")).not.toBeNull();
  });
});
