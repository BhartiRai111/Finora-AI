"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { currentMonth } from "./finance";

type MonthValue = { month: string; setMonth: (month: string) => void };

const MonthContext = createContext<MonthValue | null>(null);

/** The month the person is looking at, shared by the header's month selector, the dashboard, the budget planner and the calendar. */
export function MonthProvider({ initial, children }: { initial?: string; children: ReactNode }) {
  const [month, setMonthState] = useState(initial ?? currentMonth());
  const setMonth = useCallback((next: string) => { if (/^\d{4}-(0[1-9]|1[0-2])$/.test(next)) setMonthState(next); }, []);
  const value = useMemo(() => ({ month, setMonth }), [month, setMonth]);
  return <MonthContext.Provider value={value}>{children}</MonthContext.Provider>;
}

/** The shared month when inside a provider; otherwise a month local to the caller, so a screen also works on its own. */
export function useSelectedMonth(initial?: string): [string, (month: string) => void] {
  const shared = useContext(MonthContext);
  const [local, setLocal] = useState(initial ?? currentMonth());
  return shared ? [shared.month, shared.setMonth] : [local, setLocal];
}

export function useMonthContext(): MonthValue | null {
  return useContext(MonthContext);
}
