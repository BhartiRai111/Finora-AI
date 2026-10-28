"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { useSession } from "@/lib/session";
import { useTheme } from "@/lib/theme";
import { Icon } from "../ui/Icon";

const initials = (name: string): string => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]!.toUpperCase()).join("") || "?";

/** The signed-in person's menu: who they are (from the session, nothing invented), their account, settings, theme and sign-out. */
export function AccountMenu() {
  const { state, logout } = useSession();
  const [theme, setTheme] = useTheme();
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
    <div className="menu-wrap" ref={root} onKeyDown={onKeyDown}>
      <button ref={trigger} type="button" className="account-trigger" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined} onClick={() => setOpen((v) => !v)}>
        <span className="avatar" aria-hidden="true">{initials(name)}</span>
        <span className="account-name">{name}</span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Account" className="popup">
          <p className="account-email muted"><strong>{name}</strong><br />{user.email}</p>
          <div className="popup-sep" role="separator" />
          <Link role="menuitem" href="/account" onClick={() => close(false)}><Icon name="user" />Account and security</Link>
          <Link role="menuitem" href="/finance/regional" onClick={() => close(false)}><Icon name="settings" />Settings</Link>
          <button role="menuitem" type="button" className="menu-item" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
            <Icon name={theme === "dark" ? "sun" : "moon"} />{theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
          </button>
          <div className="popup-sep" role="separator" />
          <button role="menuitem" type="button" className="menu-item" onClick={() => { close(false); void logout(); }}><Icon name="logout" />Sign out</button>
        </div>
      )}
    </div>
  );
}
