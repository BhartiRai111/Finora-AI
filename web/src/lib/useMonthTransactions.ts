"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "./api";
import { monthBounds } from "./calendar";
import { onDataChanged } from "./dataEvents";
import type { Transaction, TransactionPage } from "./finance";

const PAGE = 200;
const MAX_PAGES = 10;

export type MonthTransactions =
  | { status: "loading" }
  | { status: "error"; error: unknown }
  | { status: "ready"; items: Transaction[]; truncated: boolean };

type Stored = { month: string; result: MonthTransactions };

/**
 * Every posted transaction of one month in one workspace, read page by page from the server (the same endpoint and filters as the Transactions
 * screen). It refetches when the month changes, when `reload` is called, and when any screen announces that data changed. Nothing is cached.
 */
export function useMonthTransactions(space: string, month: string): MonthTransactions & { reload: () => void } {
  const [stored, setStored] = useState<Stored>({ month: "", result: { status: "loading" } });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const { from, to } = monthBounds(month);
    (async () => {
      const items: Transaction[] = [];
      let cursor: string | null = null;
      let pages = 0;
      do {
        const q = new URLSearchParams({ from, to, limit: String(PAGE) });
        if (cursor) q.set("cursor", cursor);
        const page: TransactionPage = await api<TransactionPage>(`/v1/spaces/${space}/transactions?${q.toString()}`, { signal: controller.signal });
        items.push(...page.items.filter((t) => t.status === "POSTED"));
        cursor = page.next_cursor;
        pages += 1;
      } while (cursor && pages < MAX_PAGES);
      return { items, truncated: cursor !== null };
    })()
      .then((r) => { if (!controller.signal.aborted) setStored({ month, result: { status: "ready", ...r } }); })
      .catch((error: unknown) => { if (!controller.signal.aborted) setStored({ month, result: { status: "error", error } }); });
    return () => controller.abort();
  }, [space, month, tick]);

  useEffect(() => onDataChanged(() => setTick((n) => n + 1)), []);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  // Data that belongs to another month is never shown for this one: until this month arrives the state is "loading".
  const result: MonthTransactions = stored.month === month ? stored.result : { status: "loading" };
  return { ...result, reload };
}
