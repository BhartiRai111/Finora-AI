"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { getActiveWorkspaceId, setActiveWorkspaceId } from "@/lib/activeWorkspace";
import { BUSINESS_SECTIONS, PERSONAL_SECTIONS, businessSectionVisible } from "@/lib/sections";
import { useSession } from "@/lib/session";
import type { Workspace } from "@/lib/types";
import { AddExpenseDialog } from "./finance/AddExpenseDialog";
import { AccountMenu } from "./shell/AccountMenu";
import { NotificationsMenu } from "./shell/NotificationsMenu";
import { QuickSearch, type Destination } from "./shell/QuickSearch";
import { Icon } from "./ui/Icon";

/** The top bar: brand, main areas, workspace switcher, quick search, Add expense, notifications, settings and the account menu. */
export function Header() {
  const { state } = useSession();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [chosen, setChosen] = useState<string>("");
  const [adding, setAdding] = useState(false);

  // A workspace created or joined since the list was loaded is not in it yet: the list is fetched again once when the page is on such a workspace.
  const onPage = /^\/workspaces\/([^/]+)/.exec(pathname)?.[1];
  const stale = Boolean(onPage) && workspaces.length > 0 && !workspaces.some((w) => w.id === onPage);

  useEffect(() => {
    if (state.status !== "ready") return;
    const controller = new AbortController();
    api<{ items: Workspace[] }>("/v1/workspaces", { signal: controller.signal })
      .then((r) => {
        setWorkspaces(r.items);
        const stored = getActiveWorkspaceId();
        const found = r.items.find((w) => w.id === stored) ?? r.items[0];
        if (found) setChosen(found.id);
      })
      .catch(() => setWorkspaces([]));
    return () => controller.abort();
  }, [state.status, stale]);

  const visible = state.status === "ready" ? workspaces : [];
  // The page you are on wins over the remembered choice, so the switcher never disagrees with the screen.
  const onWorkspace = /^\/workspaces\/([^/]+)/.exec(pathname)?.[1];
  const personal = visible.find((w) => w.type === "PERSONAL");
  const current = onWorkspace && visible.some((w) => w.id === onWorkspace) ? onWorkspace : pathname.startsWith("/finance") && personal ? personal.id : chosen;
  const currentWs = visible.find((w) => w.id === current);

  const destinations = useMemo<Destination[]>(() => {
    const list: Destination[] = [];
    if (currentWs?.type === "BUSINESS") {
      for (const s of BUSINESS_SECTIONS) if (businessSectionVisible(currentWs.role, s.id)) list.push({ label: s.label, href: `/workspaces/${currentWs.id}/${s.id}`, icon: s.icon, hint: s.group });
    } else if (personal) {
      for (const s of PERSONAL_SECTIONS) list.push({ label: s.label, href: `/finance/${s.id}`, icon: s.icon, hint: s.group });
    }
    list.push({ label: "Workspaces", href: "/workspaces", icon: "briefcase" }, { label: "Account and security", href: "/account", icon: "user" });
    return list;
  }, [currentWs, personal]);

  function switchTo(id: string) {
    setChosen(id);
    setActiveWorkspaceId(id);
    const target = visible.find((w) => w.id === id);
    router.push(target?.type === "PERSONAL" ? "/finance" : `/workspaces/${id}`);
  }

  const notificationsHref = currentWs ? (currentWs.type === "PERSONAL" ? "/finance/notifications" : businessSectionVisible(currentWs.role, "notifications") ? `/workspaces/${currentWs.id}/notifications` : null) : null;
  const settingsHref = currentWs?.type === "BUSINESS" && businessSectionVisible(currentWs.role, "settings") ? `/workspaces/${currentWs.id}/settings` : "/finance/regional";

  return (
    <header className="site-header">
      <Link href="/" className="brand">
        <span className="brand-mark" aria-hidden="true">F</span>
        Finora AI
      </Link>
      {state.status === "ready" ? (
        <>
          <nav aria-label="Primary" className="top-nav">
            <Link href="/finance" aria-current={pathname.startsWith("/finance") ? "page" : undefined}>Personal finance</Link>
            <Link href="/workspaces" aria-current={pathname === "/workspaces" ? "page" : undefined}>Workspaces</Link>
          </nav>
          <div className="spacer" />
          <div className="header-actions">
            {visible.length > 0 && (
              <>
                <label htmlFor="workspace-switcher" className="visually-hidden">Active workspace</label>
                <select id="workspace-switcher" className="header-workspace" value={current} onChange={(e) => switchTo(e.target.value)}>
                  {visible.map((w) => <option key={w.id} value={w.id}>{w.type === "PERSONAL" ? "Personal profile" : w.name}</option>)}
                </select>
              </>
            )}
            <QuickSearch destinations={destinations} onNavigate={(href) => router.push(href)} onAction={personal ? { label: "Add an expense", run: () => setAdding(true) } : undefined} />
            {personal && (
              <button type="button" className="btn-primary" onClick={() => setAdding(true)} aria-haspopup="dialog">
                <Icon name="plus" /><span className="add-label">Add expense</span><span className="visually-hidden add-sr"> (opens a form)</span>
              </button>
            )}
            <NotificationsMenu space={current || null} allHref={notificationsHref} />
            <Link href={settingsHref} className="icon-btn" aria-label="Settings" style={{ display: "inline-grid", placeItems: "center", width: "2.5rem", height: "2.5rem", borderRadius: "var(--r-md)", color: "var(--text-muted)" }}>
              <Icon name="settings" size={20} />
            </Link>
            <AccountMenu />
          </div>
          {personal && <AddExpenseDialog open={adding} space={personal.id} onClose={() => setAdding(false)} />}
        </>
      ) : (
        <>
          <div className="spacer" />
          {state.status === "anonymous" && (
            <div className="header-actions">
              <Link href="/login">Sign in</Link>
              <Link href="/register" className="btn-primary" style={{ padding: "0.45rem 0.9rem", borderRadius: "var(--r-md)", color: "var(--on-accent)", textDecoration: "none", fontWeight: 600 }}>Create account</Link>
            </div>
          )}
        </>
      )}
    </header>
  );
}
