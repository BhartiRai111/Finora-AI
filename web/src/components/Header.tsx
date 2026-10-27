"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { getActiveWorkspaceId, setActiveWorkspaceId } from "@/lib/activeWorkspace";
import { useSession } from "@/lib/session";
import type { Workspace } from "@/lib/types";
import { AccountMenu } from "./shell/AccountMenu";

/** Brand, workspace switcher (follows the page you are on), main links and the account menu. */
export function Header() {
  const { state } = useSession();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [chosen, setChosen] = useState<string>("");

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
  const personalId = visible.find((w) => w.type === "PERSONAL")?.id;
  const current = onWorkspace && visible.some((w) => w.id === onWorkspace) ? onWorkspace : pathname.startsWith("/finance") && personalId ? personalId : chosen;

  function switchTo(id: string) {
    setChosen(id);
    setActiveWorkspaceId(id);
    const target = visible.find((w) => w.id === id);
    router.push(target?.type === "PERSONAL" ? "/finance" : `/workspaces/${id}`);
  }

  return (
    <header className="site-header">
      <Link href="/" className="brand">
        Finora AI
      </Link>
      {state.status === "ready" && (
        <nav aria-label="Primary">
          {visible.length > 0 && (
            <>
              <label htmlFor="workspace-switcher" className="visually-hidden">
                Active workspace
              </label>
              <select id="workspace-switcher" value={current} onChange={(e) => switchTo(e.target.value)}>
                {visible.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.type === "PERSONAL" ? "Personal profile" : w.name}
                  </option>
                ))}
              </select>
            </>
          )}
          <Link href="/finance" aria-current={pathname.startsWith("/finance") ? "page" : undefined}>Personal finance</Link>
          <Link href="/workspaces" aria-current={pathname === "/workspaces" ? "page" : undefined}>Workspaces</Link>
          <AccountMenu />
        </nav>
      )}
    </header>
  );
}
