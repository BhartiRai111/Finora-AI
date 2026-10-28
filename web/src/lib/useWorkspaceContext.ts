"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "./api";
import { getActiveWorkspaceId, setActiveWorkspaceId } from "./activeWorkspace";
import { useSession } from "./session";
import type { Workspace } from "./types";

/**
 * Which workspaces the signed-in person belongs to and which one the screen is about. The page being viewed wins over the remembered choice, so
 * the switcher never disagrees with what is on screen; a workspace created or joined since the list was loaded triggers one fresh read.
 */
export function useWorkspaceContext() {
  const { state } = useSession();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [chosen, setChosen] = useState("");

  const onPage = /^\/workspaces\/([^/]+)/.exec(pathname)?.[1];
  const stale = Boolean(onPage) && workspaces.length > 0 && !workspaces.some((w) => w.id === onPage);

  useEffect(() => {
    if (state.status !== "ready") return;
    const controller = new AbortController();
    api<{ items: Workspace[] }>("/v1/workspaces", { signal: controller.signal })
      .then((r) => {
        setWorkspaces(r.items);
        const found = r.items.find((w) => w.id === getActiveWorkspaceId()) ?? r.items[0];
        if (found) setChosen(found.id);
      })
      .catch(() => setWorkspaces([]));
    return () => controller.abort();
  }, [state.status, stale]);

  const visible = state.status === "ready" ? workspaces : [];
  const personal = visible.find((w) => w.type === "PERSONAL");
  const current = onPage && visible.some((w) => w.id === onPage) ? onPage : pathname.startsWith("/finance") && personal ? personal.id : chosen;
  const currentWs = visible.find((w) => w.id === current);

  function switchTo(id: string) {
    setChosen(id);
    setActiveWorkspaceId(id);
    const target = visible.find((w) => w.id === id);
    router.push(target?.type === "PERSONAL" ? "/finance" : `/workspaces/${id}`);
  }

  return { ready: state.status === "ready", pathname, workspaces: visible, personal, current, currentWs, switchTo };
}
