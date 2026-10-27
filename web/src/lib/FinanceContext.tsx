"use client";

import { createContext, useContext, useMemo, useRef, type ReactNode } from "react";
import { api, apiDownload, apiUpload } from "./api";
import { newKey } from "./finance";

type FinanceValue = {
  space: string;
  /** GET a finance resource, e.g. get("/accounts"). */
  get: <T>(path: string, signal?: AbortSignal) => Promise<T>;
  /** Write with an idempotency key; pass the same `key` again to retry the same intent safely. */
  send: <T>(method: "POST" | "PATCH" | "PUT" | "DELETE", path: string, body?: unknown, key?: string) => Promise<T>;
  /** POST a file as the raw body, e.g. upload("/finance-imports?kind=STATEMENT&filename=a.csv", file, "text/csv"). */
  upload: <T>(path: string, file: Blob, contentType: string) => Promise<T>;
  /** GET a file; the result carries the server-suggested file name. */
  download: (path: string) => Promise<{ blob: Blob; filename: string }>;
};

const Ctx = createContext<FinanceValue | null>(null);

export function FinanceProvider({ space, children }: { space: string; children: ReactNode }) {
  const value = useMemo<FinanceValue>(
    () => ({
      space,
      get: (path, signal) => api(`/v1/spaces/${space}${path}`, { signal }),
      upload: (path, file, contentType) => apiUpload(`/v1/spaces/${space}${path}`, file, contentType),
      download: (path) => apiDownload(`/v1/spaces/${space}${path}`),
      send: (method, path, body, key) => api(`/v1/spaces/${space}${path}`, { method, body, idempotencyKey: key ?? (method === "POST" ? newKey() : undefined) }),
    }),
    [space],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useFinance(): FinanceValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useFinance must be used inside FinanceProvider");
  return v;
}

/**
 * One idempotency key per user intent. The key is kept while a submit is failing (so a retry after a network error or
 * timeout cannot create a duplicate) and replaced once the action succeeded.
 */
export function useIntentKey(): { key: () => string; done: () => void } {
  const ref = useRef<string | null>(null);
  return {
    key: () => {
      ref.current ??= newKey();
      return ref.current;
    },
    done: () => {
      ref.current = null;
    },
  };
}
