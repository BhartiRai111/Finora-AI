"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { Paged } from "./business";
import { useFinance } from "./FinanceContext";

export const PAGE_SIZE = 20;

type Loaded<T> = { key: string; data?: Paged<T>; error?: unknown };

/**
 * Loads one page of a business-finance list. Filters are passed as an object; changing them returns to the first page.
 * `loading` is derived (the loaded key differs from the wanted key), so there is no state set synchronously in an effect.
 */
export function usePaged<T>(path: string, params: Record<string, string>) {
  const finance = useFinance();
  const paramsKey = JSON.stringify(Object.entries(params).filter(([, v]) => v !== "").sort());
  const [position, setPosition] = useState({ paramsKey: "", offset: 0 });
  const [tick, setTick] = useState(0);
  const [loaded, setLoaded] = useState<Loaded<T> | null>(null);
  const offset = position.paramsKey === paramsKey ? position.offset : 0;
  const key = `${path}|${paramsKey}|${offset}|${tick}`;

  const query = useMemo(() => {
    const p = new URLSearchParams(JSON.parse(paramsKey) as [string, string][]);
    p.set("limit", String(PAGE_SIZE));
    p.set("offset", String(offset));
    return p.toString();
  }, [paramsKey, offset]);

  useEffect(() => {
    const controller = new AbortController();
    finance
      .get<Paged<T>>(`${path}?${query}`, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setLoaded({ key, data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setLoaded({ key, error });
      });
    return () => controller.abort();
  }, [finance, path, query, key]);

  const current = loaded && loaded.key === key ? loaded : null;
  const reload = useCallback(() => setTick((n) => n + 1), []);
  const goTo = useCallback((next: number) => setPosition({ paramsKey, offset: Math.max(0, next) }), [paramsKey]);
  return {
    loading: current === null,
    error: current?.error,
    items: current?.data?.items ?? [],
    total: current?.data?.total ?? 0,
    offset,
    reload,
    goTo,
  };
}
