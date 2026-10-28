"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { usePersistentFlag } from "@/lib/usePersistentFlag";
import type { NavGroup } from "@/lib/sections";
import { Icon, type IconName } from "../ui/Icon";

/**
 * Sets the browser tab title and keeps it: the framework's own metadata handling can write its default title again after a client-side
 * navigation, so a change that is not ours is put back. Stops when the page changes or unmounts.
 */
function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title;
    // Watch the whole head: the framework may replace the <title> element itself, not just its text.
    const observer = new MutationObserver(() => {
      if (document.title !== title) document.title = title;
    });
    observer.observe(document.head, { childList: true, characterData: true, subtree: true });
    return () => observer.disconnect();
  }, [title]);
}

/**
 * The authenticated application frame: a collapsible sidebar of labelled, icon-led sections (a drawer on small screens), a page title with a
 * short description, and the page itself. It holds no data and makes no requests, so it cannot show anything the page did not load.
 */
export function AppShell({ contextLabel, badge, nav, currentId, title, description, eyebrow, actions, topbar, rail, switcher, sidebarExtra, footLinks = [], children }: {
  contextLabel: string;
  badge?: string;
  nav: NavGroup[];
  currentId: string;
  title: string;
  description?: string;
  /** Small label above the page title (defaults to the workspace name). */
  eyebrow?: string;
  /** Tools at the right of the page header (search, month, export, notifications, add, account). */
  actions?: ReactNode;
  /** The strip above the title (back, breadcrumb, search, notifications). */
  topbar?: ReactNode;
  /** The slim icon column at the left edge. */
  rail?: ReactNode;
  /** The profile and workspace switcher shown at the top of the sidebar. */
  switcher?: ReactNode;
  /** Extra sidebar content, such as the accounts list and the budget status. */
  sidebarExtra?: ReactNode;
  /** Links pinned to the bottom of the sidebar (Settings, Help Center). */
  footLinks?: { label: string; href: string; icon: IconName }[];
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [collapsed, setCollapsed] = usePersistentFlag("finora.sidebar.collapsed");
  const navId = useId();
  const drawer = useRef<HTMLElement>(null);

  useDocumentTitle(`${title} · ${contextLabel} · Finora AI`);

  // Escape closes the small-screen drawer.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <div className={["shell", collapsed && "collapsed", rail && "has-rail"].filter(Boolean).join(" ")}>
      {rail}
      <div className="shell-nav-toggle">
        <button type="button" aria-expanded={open} aria-controls={navId} onClick={() => setOpen((v) => !v)}>
          <Icon name={open ? "close" : "menu"} />
          {open ? "Hide sections" : "Sections"}
        </button>
        <span className="muted" aria-hidden="true">{title}</span>
      </div>
      <div className={open ? "shell-backdrop open" : "shell-backdrop"} onClick={() => setOpen(false)} aria-hidden="true" />
      <nav id={navId} ref={drawer} aria-label={`${contextLabel} sections`} className={open ? "shell-nav open" : "shell-nav"}>
        <Link href="/" className="shell-brand" title="Finora AI home">
          <span className="brand-mark" aria-hidden="true">F</span>
          <span className="shell-label">Finora AI</span>
        </Link>
        {switcher && <div className="shell-switch">{switcher}</div>}
        <p className="shell-context"><strong>{contextLabel}</strong>{badge && <><br /><span className="badge">{badge}</span></>}</p>
        {nav.map((group) => (
          <div key={group.label} className="shell-group" role="group" aria-label={group.label}>
            <p className="shell-group-label" aria-hidden="true">{group.label}</p>
            <ul>
              {group.items.map((item) => (
                <li key={item.id}>
                  <Link href={item.href} onClick={() => setOpen(false)} aria-current={item.id === currentId ? "page" : undefined} className={item.id === currentId ? "shell-link current" : "shell-link"} title={collapsed ? item.label : undefined}>
                    <Icon name={item.icon} />
                    <span className="shell-label">{item.label}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {sidebarExtra && <div className="shell-extra">{sidebarExtra}</div>}
        <div className="shell-foot">
          {footLinks.map((l) => (
            <Link key={l.href} href={l.href} className="shell-link" onClick={() => setOpen(false)} title={collapsed ? l.label : undefined}><Icon name={l.icon} /><span className="shell-label">{l.label}</span></Link>
          ))}
          <button type="button" className="shell-collapse shell-link" onClick={() => setCollapsed(!collapsed)} aria-pressed={collapsed} title={collapsed ? "Expand the sidebar" : "Collapse the sidebar"}>
          <Icon name="panelLeft" />
          <span className="shell-label">{collapsed ? "Expand sidebar" : "Collapse sidebar"}</span>
          </button>
        </div>
      </nav>
      <main id="main" className="shell-main" tabIndex={-1}>
        {topbar}
        <div className="page-head">
          <div className="page-head-text">
            <p className="eyebrow">{eyebrow ?? contextLabel}</p>
            <h1>{title}</h1>
            {description && <p className="shell-description">{description}</p>}
          </div>
          {actions}
        </div>
        {children}
      </main>
    </div>
  );
}
