// Date helpers for the calendar. Dates are plain "YYYY-MM-DD" strings (a business date has no time zone); month cursors are "YYYY-MM".
// Everything uses UTC arithmetic only to count days, so the user's browser time zone can never shift a date.

export type Cell = { date: string; inMonth: boolean };

const pad = (n: number) => String(n).padStart(2, "0");

function parseMonth(month: string): [number, number] {
  const [y = 1970, m = 1] = month.split("-").map(Number);
  return [y, m];
}

export function shiftMonth(month: string, delta: number): string {
  const [y, m] = parseMonth(month);
  const d = new Date(Date.UTC(y, m - 1 + delta, 1));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

export function monthBounds(month: string): { from: string; to: string } {
  const [y, m] = parseMonth(month);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${pad(last)}` };
}

export function monthLabel(month: string): string {
  const [y, m] = parseMonth(month);
  return new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, 1)));
}

export function dayLabel(date: string): string {
  const [y = 1970, m = 1, d = 1] = date.split("-").map(Number);
  return new Intl.DateTimeFormat("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, d)));
}

/** Monday first (weekStartsOn = 1) or Sunday first (0). */
export function weekdayNames(weekStartsOn: 0 | 1 = 1): string[] {
  const base = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  return [...base.slice(weekStartsOn), ...base.slice(0, weekStartsOn)];
}

/** The weeks shown for a month: whole weeks (5 or 6 rows) that include the month's first and last day. */
export function monthCells(month: string, weekStartsOn: 0 | 1 = 1): Cell[] {
  const [y, m] = parseMonth(month);
  const first = new Date(Date.UTC(y, m - 1, 1));
  const lead = (first.getUTCDay() - weekStartsOn + 7) % 7;
  const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const total = Math.ceil((lead + days) / 7) * 7;
  const cells: Cell[] = [];
  for (let i = 0; i < total; i++) {
    const d = new Date(Date.UTC(y, m - 1, 1 - lead + i));
    const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
    cells.push({ date, inMonth: date.startsWith(month) });
  }
  return cells;
}

export const monthOf = (date: string): string => date.slice(0, 7);
