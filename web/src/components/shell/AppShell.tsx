"use client";

import Link from "next/link";
import { useEffect, useId, useState, type ReactNode } from "react";
import type { NavGroup } from "@/lib/sections";

/**
 * The authenticated application frame: a labelled section navigation beside the content, a page title with a short description,
 * and a collapsible menu on small screens. It holds no data and makes no requests, so it cannot show anything the page did not load.
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
  const navId = useId();

  useEffect(() => {
    document.title = `${title} · ${contextLabel} · Finora AI`;
  }, [title, contextLabel]);

  return (
    <div className="shell">
      <div className="shell-nav-toggle">
        <button type="button" aria-expanded={open} aria-controls={navId} onClick={() => setOpen((v) => !v)}>
          {open ? "Hide sections" : "Sections"}
        </button>
      </div>
      <nav id={navId} aria-label={`${contextLabel} sections`} className={open ? "shell-nav open" : "shell-nav"}>
        <p className="shell-context"><strong>{contextLabel}</strong>{badge && <><br /><span className="badge">{badge}</span></>}</p>
        {nav.map((group) => (
          <div key={group.label} className="shell-group" role="group" aria-label={group.label}>
            <p className="shell-group-label" aria-hidden="true">{group.label}</p>
            <ul>
              {group.items.map((item) => (
                <li key={item.id}>
                  <Link href={item.href} onClick={() => setOpen(false)} aria-current={item.id === currentId ? "page" : undefined} className={item.id === currentId ? "shell-link current" : "shell-link"}>
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <main id="main" className="shell-main" tabIndex={-1}>
        <h1>{title}</h1>
        {description && <p className="shell-description">{description}</p>}
        {children}
      </main>
    </div>
  );
}
