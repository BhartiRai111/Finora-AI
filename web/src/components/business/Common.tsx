"use client";

import type { ReactNode } from "react";
import { describeError } from "@/lib/api";
import { STATUS_LABEL, type DisplayStatus } from "@/lib/business";
import { PAGE_SIZE } from "@/lib/usePaged";
import { Loading, Notice } from "../Notice";

export function Pager({ total, offset, onGo }: { total: number; offset: number; onGo: (offset: number) => void }) {
  if (total === 0) return null;
  const from = offset + 1;
  const to = Math.min(total, offset + PAGE_SIZE);
  return (
    <nav className="row" aria-label="Pages">
      <span className="muted">Showing {from}–{to} of {total}</span>
      <button type="button" disabled={offset === 0} onClick={() => onGo(offset - PAGE_SIZE)}>Previous</button>
      <button type="button" disabled={to >= total} onClick={() => onGo(offset + PAGE_SIZE)}>Next</button>
    </nav>
  );
}

/** Status is always written out, never colour alone. */
export function StatusBadge({ status }: { status: DisplayStatus }) {
  return <span className={`status status-${status === "OVERDUE" ? "over" : status === "PAID" ? "under" : status === "PARTIALLY_PAID" ? "warning" : "none"}`}>{STATUS_LABEL[status]}</span>;
}

/** Loading / error / empty states for a paged list; renders children (the table) when there is something to show. */
export function ListState({ loading, error, empty, emptyText, onRetry, children }: {
  loading: boolean; error: unknown; empty: boolean; emptyText: string; onRetry: () => void; children: ReactNode;
}) {
  if (error) {
    return (
      <>
        <Notice>{describeError(error)}</Notice>
        <button type="button" onClick={onRetry}>Try again</button>
      </>
    );
  }
  if (loading) return <Loading />;
  if (empty) return <p>{emptyText}</p>;
  return <>{children}</>;
}
