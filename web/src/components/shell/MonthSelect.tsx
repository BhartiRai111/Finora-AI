"use client";

import { monthLabel, shiftMonth } from "@/lib/calendar";
import { useMonthContext } from "@/lib/month";
import { Icon } from "../ui/Icon";

/** Previous and next month around the month being viewed; the dashboard, budgets and calendar all follow it. */
export function MonthSelect() {
  const ctx = useMonthContext();
  if (!ctx) return null;
  return (
    <div className="month-select" role="group" aria-label="Month">
      <button type="button" className="icon-btn" aria-label="Previous month" onClick={() => ctx.setMonth(shiftMonth(ctx.month, -1))}><Icon name="chevronLeft" size={16} /></button>
      <span className="month-label" aria-live="polite">{monthLabel(ctx.month)}</span>
      <button type="button" className="icon-btn" aria-label="Next month" onClick={() => ctx.setMonth(shiftMonth(ctx.month, 1))}><Icon name="chevronRight" size={16} /></button>
    </div>
  );
}
