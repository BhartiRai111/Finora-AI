"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { NOTIFICATION_CATEGORIES, SEVERITY_TEXT, humanizeType, type NotificationPage } from "@/lib/platform";
import { Loading, Notice } from "../Notice";

const PAGE = 20;

/** A one-line reminder above the tabs; it renders nothing when there is nothing unread or the caller may not see notifications. */
export function NotificationStrip({ onOpen, refreshKey = 0 }: { onOpen: () => void; refreshKey?: number }) {
  const finance = useFinance();
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    finance.get<{ unread: number }>("/finance-notifications/unread-count", controller.signal).then((r) => {
      if (!controller.signal.aborted) setUnread(r.unread);
    }).catch(() => {
      if (!controller.signal.aborted) setUnread(0);
    });
    return () => controller.abort();
  }, [finance, refreshKey]);
  if (unread === 0) return null;
  return (
    <p role="status" className="muted">
      You have {unread} unread notification{unread === 1 ? "" : "s"}. <button type="button" className="link" onClick={onOpen}>Open notifications</button>
    </p>
  );
}

/** The notification centre: persisted, per-user read state, paged. Notifications appear in the application only; nothing is sent by email, SMS or push. */
export function NotificationsPanel({ onChanged }: { onChanged?: () => void }) {
  const finance = useFinance();
  const [category, setCategory] = useState("");
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [offset, setOffset] = useState(0);
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<{ s: "loading" } | { s: "error"; m: string } | { s: "ready"; page: NotificationPage }>({ s: "loading" });
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    const q = new URLSearchParams({ limit: String(PAGE), offset: String(offset) });
    if (category) q.set("category", category);
    if (unreadOnly) q.set("unread_only", "true");
    finance.get<NotificationPage>(`/finance-notifications?${q.toString()}`, controller.signal).then((page) => {
      if (!controller.signal.aborted) setState({ s: "ready", page });
    }).catch((e: unknown) => {
      if (!controller.signal.aborted) setState({ s: "error", m: describeError(e) });
    });
    return () => controller.abort();
  }, [finance, category, unreadOnly, offset, tick]);

  async function act(path: string) {
    setBusy(true);
    setActionError(null);
    try {
      await finance.send("POST", path, {});
      setTick((n) => n + 1);
      onChanged?.();
    } catch (e) {
      setActionError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="notif-title">
      <h2 id="notif-title">Notifications</h2>
      <p className="muted">Notifications appear here only. No email, SMS or push delivery is configured, so nothing is sent outside the application.</p>
      {actionError && <Notice>{actionError}</Notice>}
      <div className="filters" role="group" aria-label="Notification filters">
        <div className="column">
          <label htmlFor="nf-cat">Category</label>
          <select id="nf-cat" value={category} onChange={(e) => { setCategory(e.target.value); setOffset(0); }}>
            <option value="">All categories</option>
            {NOTIFICATION_CATEGORIES.map((c) => <option key={c} value={c}>{humanizeType(c)}</option>)}
          </select>
        </div>
        <label><input type="checkbox" checked={unreadOnly} onChange={(e) => { setUnreadOnly(e.target.checked); setOffset(0); }} /> Unread only</label>
        <button type="button" disabled={busy || state.s !== "ready" || state.page.unread === 0} onClick={() => void act("/finance-notifications/read-all")}>Mark all as read</button>
      </div>
      {state.s === "loading" && <Loading label="Loading notifications…" />}
      {state.s === "error" && <><Notice>{state.m}</Notice><button type="button" onClick={() => setTick((n) => n + 1)}>Try again</button></>}
      {state.s === "ready" && (
        <>
          <p role="status">{state.page.unread} unread. {state.page.total} notification{state.page.total === 1 ? "" : "s"} match the current filter.</p>
          {state.page.items.length === 0 && <p>{unreadOnly || category ? "No notifications match these filters." : "You have no notifications."}</p>}
          <ul className="list">
            {state.page.items.map((n) => (
              <li key={n.id} className={n.read ? "muted" : undefined}>
                <strong>{n.title}</strong> <span className="muted">({humanizeType(n.category)} · {SEVERITY_TEXT[n.severity]} · {n.created_at.slice(0, 10)}{n.read ? " · read" : " · unread"})</span>
                {n.body && <div>{n.body}</div>}
                {!n.read && <button type="button" disabled={busy} onClick={() => void act(`/finance-notifications/${n.id}/read`)} aria-label={`Mark "${n.title}" as read`}>Mark as read</button>}
              </li>
            ))}
          </ul>
          <div className="filters" role="group" aria-label="Pages">
            <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
            <span>{state.page.total === 0 ? "0" : `${offset + 1}-${Math.min(offset + PAGE, state.page.total)}`} of {state.page.total}</span>
            <button type="button" disabled={offset + PAGE >= state.page.total} onClick={() => setOffset(offset + PAGE)}>Next</button>
          </div>
        </>
      )}
    </section>
  );
}
