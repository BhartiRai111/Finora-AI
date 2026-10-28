"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { api, describeError } from "@/lib/api";
import { onDataChanged } from "@/lib/dataEvents";
import type { NotificationPage } from "@/lib/platform";
import { Icon } from "../ui/Icon";

type State = { s: "idle" } | { s: "loading" } | { s: "denied" } | { s: "error"; m: string } | { s: "ready"; page: NotificationPage };

async function fetchState(space: string): Promise<State> {
  try {
    return { s: "ready", page: await api<NotificationPage>(`/v1/spaces/${space}/finance-notifications?limit=5`) };
  } catch (e) {
    const status = (e as { status?: number }).status;
    return status === 403 || status === 404 ? { s: "denied" } : { s: "error", m: describeError(e) };
  }
}

/**
 * The bell in the top bar: the unread count and the latest notifications of the workspace being viewed, from the server. A role that may not
 * read notifications gets no count and a short explanation, never a made-up number. Nothing here is delivered anywhere else.
 */
export function NotificationsMenu({ space, allHref }: { space: string | null; allHref: string | null }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<State>({ s: "idle" });
  const [busy, setBusy] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  const load = useCallback(() => (space ? fetchState(space) : Promise.resolve<State>({ s: "idle" })), [space]);

  useEffect(() => {
    let live = true;
    const run = () => { void load().then((next) => { if (live) setState(next); }); };
    run();
    const off = onDataChanged(run);
    return () => { live = false; off(); };
  }, [load]);

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (root.current && !root.current.contains(e.target as Node)) setOpen(false); };
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") { setOpen(false); trigger.current?.focus(); } };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", key);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", key); };
  }, [open]);

  async function markAll() {
    if (!space || busy) return;
    setBusy(true);
    try {
      await api(`/v1/spaces/${space}/finance-notifications/read-all`, { method: "POST", body: {} });
      setState(await load());
    } catch (e) {
      setState({ s: "error", m: describeError(e) });
    } finally {
      setBusy(false);
    }
  }

  if (!space) return null;
  const unread = state.s === "ready" ? state.page.unread : 0;
  return (
    <div className="menu-wrap" ref={root}>
      <button ref={trigger} type="button" className="icon-btn" aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"} aria-expanded={open} aria-controls={open ? panelId : undefined} onClick={() => setOpen((v) => !v)} style={{ position: "relative" }}>
        <Icon name="bell" size={20} />
        {unread > 0 && <span className="bell-dot" aria-hidden="true">{unread > 9 ? "9+" : unread}</span>}
      </button>
      {open && (
        <section id={panelId} role="dialog" aria-label="Notifications" className="popup popup-wide">
          <p className="popup-title">Notifications</p>
          {state.s === "idle" && <p className="muted" style={{ padding: "0 0.75rem" }}>Loading…</p>}
          {state.s === "denied" && <p className="muted" style={{ padding: "0 0.75rem" }}>Your role in this workspace does not include notifications.</p>}
          {state.s === "error" && <p role="alert" className="muted" style={{ padding: "0 0.75rem" }}>{state.m}</p>}
          {state.s === "ready" && state.page.items.length === 0 && <p className="muted" style={{ padding: "0 0.75rem" }}>You have no notifications.</p>}
          {state.s === "ready" && state.page.items.map((n) => (
            <div key={n.id} className={n.read ? "notif-item" : "notif-item unread"}>
              <strong>{n.title}</strong>
              <span>{n.created_at.slice(0, 10)}{n.read ? "" : " · unread"}{n.body ? ` · ${n.body}` : ""}</span>
            </div>
          ))}
          <div className="popup-sep" role="separator" />
          <div className="row" style={{ padding: "0.25rem 0.5rem", justifyContent: "space-between" }}>
            <button type="button" className="link" onClick={() => void markAll()} disabled={busy || unread === 0}>Mark all as read</button>
            {allHref && <Link href={allHref} onClick={() => setOpen(false)}>View all</Link>}
          </div>
          <p className="muted" style={{ padding: "0 0.75rem", margin: 0, fontSize: "0.75rem" }}>Notifications appear in the app only.</p>
        </section>
      )}
    </div>
  );
}
