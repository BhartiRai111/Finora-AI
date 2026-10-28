"use client";

import { useCallback, useSyncExternalStore } from "react";

const listeners = new Set<() => void>();

function read(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

/** A boolean remembered in this browser (a per-person convenience such as "sidebar collapsed"); false on the server and when storage is unavailable. */
export function usePersistentFlag(key: string): [boolean, (next: boolean) => void] {
  const value = useSyncExternalStore(
    (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
    () => read(key),
    () => false,
  );
  const set = useCallback((next: boolean) => {
    try {
      window.localStorage.setItem(key, next ? "1" : "0");
    } catch {
      // storage unavailable: the choice then lasts only until the next change event in this tab
    }
    listeners.forEach((l) => l());
  }, [key]);
  return [value, set];
}
