"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { usePersistentFlag } from "@/lib/usePersistentFlag";
import type { NavGroup } from "@/lib/sections";
import { Icon } from "../ui/Icon";

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
export function AppShell({ contextLabel, badge, nav, currentId, title, description, children }: {
  contextLabel: string;
  badge?: string;
  nav: NavGroup[];
  currentId: string;
  title: string;
  description?: string;
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
    <div className={collapsed ? "shell collapsed" : "shell"}>
      <div className="shell-nav-toggle">
        <button type="button" aria-expanded={open} aria-controls={navId} onClick={() => setOpen((v) => !v)}>
          <Icon name={open ? "close" : "menu"} />
          {open ? "Hide sections" : "Sections"}
        </button>
        <span className="muted" aria-hidden="true">{title}</span>
      </div>
      <div className={open ? "shell-backdrop open" : "shell-backdrop"} onClick={() => setOpen(false)} aria-hidden="true" />
      <nav id={navId} ref={drawer} aria-label={`${contextLabel} sections`} className={open ? "shell-nav open" : "shell-nav"}>
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
        <button type="button" className="shell-collapse" onClick={() => setCollapsed(!collapsed)} aria-pressed={collapsed} title={collapsed ? "Expand the sidebar" : "Collapse the sidebar"}>
          <Icon name="panelLeft" />
          <span className="shell-label">{collapsed ? "Expand sidebar" : "Collapse sidebar"}</span>
        </button>
      </nav>
      <main id="main" className="shell-main" tabIndex={-1}>
        <h1>{title}</h1>
        {description && <p className="shell-description">{description}</p>}
        {children}
      </main>
    </div>
  );
}
