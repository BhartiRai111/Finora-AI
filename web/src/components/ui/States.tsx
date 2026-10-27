"use client";

import type { ReactNode } from "react";
import { describeError } from "@/lib/api";
import { Notice } from "../Notice";

/** A failed load: a safe message (never server internals) and one way to try again. */
export function ErrorState({ error, onRetry, message }: { error?: unknown; onRetry?: () => void; message?: string }) {
  return (
    <div className="state state-error">
      <Notice>{message ?? describeError(error)}</Notice>
      {onRetry && <button type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

/** Nothing to show yet. Says what is missing and, where useful, what to do next; never shows invented data. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="state state-empty">
      <p><strong>{title}</strong></p>
      {children && <p className="muted">{children}</p>}
      {action}
    </div>
  );
}

/** The signed-in user may not open this area. The server enforces this on every request; this screen only explains it. */
export function AccessDenied({ children }: { children?: ReactNode }) {
  return (
    <div className="state state-denied" role="alert">
      <p><strong>You do not have access to this section.</strong></p>
      <p className="muted">{children ?? "Your role in this workspace does not include it. Ask a workspace Owner or Admin if you think this is a mistake."}</p>
    </div>
  );
}

/** Placeholder blocks while content loads; announced once as a status, not read block by block. */
export function Skeleton({ label = "Loading…", lines = 3 }: { label?: string; lines?: number }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" className="skeleton">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton-line" aria-hidden="true" style={{ width: `${90 - i * 18}%` }} />)}
    </div>
  );
}
