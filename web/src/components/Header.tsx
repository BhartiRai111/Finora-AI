"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { getActiveWorkspaceId, setActiveWorkspaceId } from "@/lib/activeWorkspace";
import { useSession } from "@/lib/session";
import type { Workspace } from "@/lib/types";

/** Brand, workspace switcher (always shows the active workspace) and sign-out. */
export function Header() {
  const { state, logout } = useSession();
  const router = useRouter();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [active, setActive] = useState<string>("");

  useEffect(() => {
    if (state.status !== "ready") return;
    const controller = new AbortController();
    api<{ items: Workspace[] }>("/v1/workspaces", { signal: controller.signal })
      .then((r) => {
        setWorkspaces(r.items);
        const stored = getActiveWorkspaceId();
        const chosen = r.items.find((w) => w.id === stored) ?? r.items[0];
        if (chosen) setActive(chosen.id);
      })
      .catch(() => setWorkspaces([]));
    return () => controller.abort();
  }, [state.status]);

  const visible = state.status === "ready" ? workspaces : [];

  function switchTo(id: string) {
    setActive(id);
    setActiveWorkspaceId(id);
    router.push(`/workspaces/${id}`);
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
              <select id="workspace-switcher" value={active} onChange={(e) => switchTo(e.target.value)}>
                {visible.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.type === "PERSONAL" ? "Personal profile" : w.name}
                  </option>
                ))}
              </select>
            </>
          )}
          <Link href="/workspaces">Workspaces</Link>
          <Link href="/account">Account</Link>
          <button type="button" className="link" onClick={() => void logout()}>
            Sign out
          </button>
        </nav>
      )}
    </header>
  );
}
