"use client";

import { useWorkspaceContext } from "@/lib/useWorkspaceContext";

/** Chooses which workspace (the personal profile or a business) the app is showing. The list and the current choice come from the server. */
export function WorkspaceSwitcher({ className }: { className?: string }) {
  const { workspaces, current, switchTo } = useWorkspaceContext();
  if (workspaces.length === 0) return null;
  return (
    <div className={className}>
      <label htmlFor="workspace-switcher" className="visually-hidden">Active workspace</label>
      <select id="workspace-switcher" value={current} onChange={(e) => switchTo(e.target.value)}>
        {workspaces.map((w) => <option key={w.id} value={w.id}>{w.type === "PERSONAL" ? "Personal profile" : w.name}</option>)}
      </select>
    </div>
  );
}
