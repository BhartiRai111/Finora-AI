"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { useSession } from "@/lib/session";

/** The signed-in user's menu: who they are (from the session, nothing invented), their account page and sign-out. */
export function AccountMenu() {
  const { state, logout } = useSession();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const away = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  if (state.status !== "ready") return null;
  const user = state.user;
  const name = user.display_name || user.email;

  function close(returnFocus: boolean) {
    setOpen(false);
    if (returnFocus) trigger.current?.focus();
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      close(true);
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const items = Array.from(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    if (items.length === 0) return;
    event.preventDefault();
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next = event.key === "ArrowDown" ? (index + 1) % items.length : (index - 1 + items.length) % items.length;
    items[next]!.focus();
  }

  return (
    <div className="account-menu" ref={root} onKeyDown={onKeyDown}>
      <button ref={trigger} type="button" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined} onClick={() => setOpen((v) => !v)}>
        <span className="account-name">{name}</span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Account" className="account-popup">
          <p className="muted account-email">{user.email}</p>
          <Link role="menuitem" href="/account" onClick={() => close(false)}>Account and security</Link>
          <button role="menuitem" type="button" onClick={() => { close(false); void logout(); }}>Sign out</button>
        </div>
      )}
    </div>
  );
}
