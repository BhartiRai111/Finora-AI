"use client";

import { useCallback, useSyncExternalStore } from "react";

export type Theme = "dark" | "light";
const KEY = "finora.theme";
const listeners = new Set<() => void>();

function current(): Theme {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}

/** The colour theme: dark by default, light on request. Remembered in this browser only (a per-person convenience, never data). */
export function useTheme(): [Theme, (next: Theme) => void] {
  const theme = useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, current, () => "dark" as Theme);
  const set = useCallback((next: Theme) => {
    document.documentElement.dataset.theme = next;
    try {
      window.localStorage.setItem(KEY, next);
    } catch {
      // storage unavailable: the choice lasts for this page only
    }
    listeners.forEach((l) => l());
  }, []);
  return [theme, set];
}

/** Applies the remembered theme before first paint (inlined in the document head by the root layout). */
export const THEME_BOOT_SCRIPT = "try{if(localStorage.getItem('finora.theme')==='light')document.documentElement.dataset.theme='light'}catch(e){}";
