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
    <div className="loading-block">
      <p role="status" aria-live="polite" className="loading">
        {label}
      </p>
      <div className="skeleton" aria-hidden="true">
        <div className="skeleton-line" style={{ width: "90%" }} />
        <div className="skeleton-line" style={{ width: "72%" }} />
      </div>
    </div>
  );
}
