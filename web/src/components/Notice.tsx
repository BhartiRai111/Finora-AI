import type { ReactNode } from "react";

export function Notice({ kind = "error", children }: { kind?: "error" | "info" | "success"; children: ReactNode }) {
  return (
    <div className={`notice notice-${kind}`} role={kind === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}

export function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <p role="status" aria-live="polite" className="loading">
      {label}
    </p>
  );
}
