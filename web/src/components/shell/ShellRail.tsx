"use client";

import Link from "next/link";
import { useSession } from "@/lib/session";
import { useTheme } from "@/lib/theme";
import { useWorkspaceContext } from "@/lib/useWorkspaceContext";
import { businessSectionVisible } from "@/lib/sections";
import { Icon } from "../ui/Icon";

/** The slim icon column at the left edge: choose the light or dark theme (remembered in this browser), Help Center, Settings and sign-out. */
export function ShellRail() {
  const { logout } = useSession();
  const [theme, setTheme] = useTheme();
  const { currentWs } = useWorkspaceContext();
  const settingsHref = currentWs?.type === "BUSINESS" && businessSectionVisible(currentWs.role, "settings") ? `/workspaces/${currentWs.id}/settings` : "/finance/regional";
  return (
    <aside className="shell-rail" aria-label="Quick controls">
      <div className="rail-group" role="group" aria-label="Theme">
        <button type="button" className="rail-btn" aria-label="Light theme" aria-pressed={theme === "light"} onClick={() => setTheme("light")}><Icon name="sun" size={20} /></button>
        <button type="button" className="rail-btn" aria-label="Dark theme" aria-pressed={theme === "dark"} onClick={() => setTheme("dark")}><Icon name="moon" size={20} /></button>
      </div>
      <div className="rail-group rail-bottom">
        <Link href="/help" className="rail-btn" aria-label="Help Center" title="Help Center"><Icon name="help" size={20} /></Link>
        <Link href={settingsHref} className="rail-btn" aria-label="Settings" title="Settings"><Icon name="settings" size={20} /></Link>
        <button type="button" className="rail-btn" aria-label="Sign out" title="Sign out" onClick={() => void logout()}><Icon name="logout" size={20} /></button>
      </div>
    </aside>
  );
}
