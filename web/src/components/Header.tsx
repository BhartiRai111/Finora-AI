"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSession } from "@/lib/session";
import { HeaderControls } from "./shell/HeaderControls";

/** Pages that carry their own sidebar and page header (personal finance and a business workspace). */
export const hasShell = (pathname: string): boolean => /^\/finance(\/|$)/.test(pathname) || /^\/workspaces\/[^/]+/.test(pathname);

/** The top bar for pages without the sidebar (landing, sign-in, workspaces list, account, help): brand, main areas and the same controls. */
export function Header() {
  const { state } = useSession();
  const pathname = usePathname() ?? "";
  if (hasShell(pathname)) return null;
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
            <Link href="/help" aria-current={pathname === "/help" ? "page" : undefined}>Help</Link>
          </nav>
          <div className="spacer" style={{ flex: 1 }} />
          <HeaderControls variant="bar" />
        </>
      ) : (
        <>
          <div className="spacer" style={{ flex: 1 }} />
          {state.status === "anonymous" && (
            <div className="head-tools">
              <Link href="/login">Sign in</Link>
              <Link href="/register" className="btn-primary btn-link">Create account</Link>
            </div>
          )}
        </>
      )}
    </header>
  );
}
