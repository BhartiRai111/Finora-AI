"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { BUSINESS_SECTIONS, PERSONAL_SECTIONS, businessSectionVisible } from "@/lib/sections";
import { useWorkspaceContext } from "@/lib/useWorkspaceContext";
import { AddTransactionDialog } from "../finance/AddTransactionDialog";
import { Icon } from "../ui/Icon";
import { NotificationsMenu } from "./NotificationsMenu";
import { QuickSearch, type Destination } from "./QuickSearch";

/**
 * The strip above the page title: back and forward, where you are (breadcrumb), the quick-search pill (the app's own sections; Ctrl or Cmd + K),
 * notifications and a link to the Help Center. Every control acts on the workspace being viewed; the server authorizes each request.
 */
export function TopBar({ trail }: { trail: string[] }) {
  const router = useRouter();
  const { ready, personal, current, currentWs } = useWorkspaceContext();
  const [adding, setAdding] = useState(false);

  const destinations = useMemo<Destination[]>(() => {
    const list: Destination[] = [];
    if (currentWs?.type === "BUSINESS") {
      for (const s of BUSINESS_SECTIONS) if (businessSectionVisible(currentWs.role, s.id)) list.push({ label: s.label, href: `/workspaces/${currentWs.id}/${s.id}`, icon: s.icon, hint: s.group });
    } else if (personal) {
      for (const s of PERSONAL_SECTIONS) list.push({ label: s.label, href: `/finance/${s.id}`, icon: s.icon, hint: s.group });
    }
    list.push({ label: "Workspaces", href: "/workspaces", icon: "briefcase" }, { label: "Account and security", href: "/account", icon: "user" }, { label: "Help Center", href: "/help", icon: "help" });
    return list;
  }, [currentWs, personal]);

  const notificationsHref = currentWs ? (currentWs.type === "PERSONAL" ? "/finance/notifications" : businessSectionVisible(currentWs.role, "notifications") ? `/workspaces/${currentWs.id}/notifications` : null) : null;

  return (
    <div className="topbar">
      <div className="topbar-trail">
        <button type="button" className="icon-btn round" aria-label="Go back" onClick={() => router.back()}><Icon name="arrowLeft" size={16} /></button>
        <button type="button" className="icon-btn round" aria-label="Go forward" onClick={() => window.history.forward()}><Icon name="arrowRight" size={16} /></button>
        <nav aria-label="Breadcrumb">
          <ol className="crumbs">
            {trail.map((t, i) => <li key={`${t}-${i}`} aria-current={i === trail.length - 1 ? "page" : undefined}>{t}</li>)}
          </ol>
        </nav>
      </div>
      {ready && (
        <div className="topbar-tools">
          <QuickSearch destinations={destinations} onNavigate={(href) => router.push(href)} onAction={personal ? { label: "Add a transaction", run: () => setAdding(true) } : undefined} />
          <NotificationsMenu space={current || null} allHref={notificationsHref} />
          <Link href="/help" className="icon-btn round" aria-label="Help Center"><Icon name="info" size={18} /></Link>
          {personal && <AddTransactionDialog open={adding} space={personal.id} onClose={() => setAdding(false)} />}
        </div>
      )}
    </div>
  );
}
