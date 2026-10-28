import { describe, expect, it } from "vitest";
import { dayLabel, monthBounds, monthCells, monthLabel, shiftMonth, weekdayNames } from "./calendar";

describe("calendar helpers", () => {
  it("moves between months across year ends", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftMonth("2026-03", 0)).toBe("2026-03");
    expect(shiftMonth("2026-03", 14)).toBe("2027-05");
  });

  it("knows month bounds, including leap years", () => {
    expect(monthBounds("2026-02")).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(monthBounds("2028-02")).toEqual({ from: "2028-02-01", to: "2028-02-29" });
    expect(monthBounds("2026-10")).toEqual({ from: "2026-10-01", to: "2026-10-31" });
  });

  it("builds whole weeks that contain every day of the month exactly once", () => {
    for (const month of ["2026-02", "2026-03", "2026-10", "2027-05", "2028-02"]) {
      for (const start of [0, 1] as const) {
        const cells = monthCells(month, start);
        expect(cells.length % 7).toBe(0);
        const inside = cells.filter((c) => c.inMonth).map((c) => c.date);
        const { from, to } = monthBounds(month);
        expect(inside[0]).toBe(from);
        expect(inside[inside.length - 1]).toBe(to);
        expect(new Set(cells.map((c) => c.date)).size).toBe(cells.length);
      }
    }
  });

  it("puts the first of the month under the right weekday", () => {
    // 1 October 2026 is a Thursday: Monday-first gives 3 leading days, Sunday-first gives 4.
    expect(monthCells("2026-10", 1).findIndex((c) => c.inMonth)).toBe(3);
    expect(monthCells("2026-10", 0).findIndex((c) => c.inMonth)).toBe(4);
    expect(weekdayNames(1)[0]).toBe("Mon");
    expect(weekdayNames(0)[0]).toBe("Sun");
  });

  it("labels months and days without shifting them by the viewer's time zone", () => {
    expect(monthLabel("2026-10")).toBe("October 2026");
    expect(dayLabel("2026-10-01")).toBe("Thursday, 1 October 2026");
  });
});
