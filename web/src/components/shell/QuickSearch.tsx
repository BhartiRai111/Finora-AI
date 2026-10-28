"use client";

import Link from "next/link";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Dialog } from "../ui/Dialog";
import { Icon, type IconName } from "../ui/Icon";

export type Destination = { label: string; href: string; icon: IconName; hint?: string };

/**
 * Quick navigation: type to filter the places this person can go and press Enter, or use the arrow keys. It searches the app's own sections
 * (not financial records), so it never needs data and cannot show anything the person may not open. Ctrl or Cmd + K opens it anywhere.
 */
export function QuickSearch({ destinations, onNavigate, onAction }: { destinations: Destination[]; onNavigate: (href: string) => void; onAction?: { label: string; run: () => void } }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const list = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen(true);
      }
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, []);

  const matches = useMemo(() => {
    const term = q.trim().toLowerCase();
    return destinations.filter((d) => !term || `${d.label} ${d.hint ?? ""}`.toLowerCase().includes(term));
  }, [destinations, q]);

  function close() {
    setOpen(false);
    setQ("");
    setActive(0);
  }

  function go(href: string) {
    close();
    onNavigate(href);
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => Math.min(matches.length - 1, i + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === "Enter" && matches[active]) {
      e.preventDefault();
      go(matches[active]!.href);
    }
  }

  return (
    <>
      <button type="button" className="search-pill" onClick={() => setOpen(true)} aria-haspopup="dialog" aria-label="Search and quick actions" title="Jump to a section (Ctrl K)">
        <Icon name="search" size={18} /><span className="search-pill-text" aria-hidden="true">Global search</span><kbd aria-hidden="true">Ctrl K</kbd>
      </button>
      <Dialog open={open} title="Search and quick actions" onClose={close}>
        <label htmlFor={`${listId}-q`} className="visually-hidden">Search sections</label>
        <input id={`${listId}-q`} data-autofocus type="search" role="combobox" aria-expanded="true" aria-controls={listId} aria-activedescendant={matches[active] ? `${listId}-${active}` : undefined} autoComplete="off" placeholder="Jump to a section…" value={q} onChange={(e) => { setQ(e.target.value); setActive(0); }} onKeyDown={onKeyDown} style={{ width: "100%" }} />
        {onAction && !q.trim() && (
          <p style={{ margin: "0.75rem 0 0" }}>
            <button type="button" className="btn-primary" onClick={() => { close(); onAction.run(); }}><Icon name="plus" />{onAction.label}</button>
          </p>
        )}
        {matches.length === 0 ? <p className="muted" role="status">Nothing matches “{q}”.</p> : (
          <ul id={listId} ref={list} role="listbox" aria-label="Sections" className="palette-list">
            {matches.map((d, i) => (
              <li key={d.href} role="option" id={`${listId}-${i}`} aria-selected={i === active}>
                <Link href={d.href} data-active={i === active} onClick={(e) => { e.preventDefault(); go(d.href); }}>
                  <Icon name={d.icon} />{d.label}{d.hint && <span className="muted">{d.hint}</span>}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Dialog>
    </>
  );
}
