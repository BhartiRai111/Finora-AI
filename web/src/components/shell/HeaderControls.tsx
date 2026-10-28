"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { BUSINESS_SECTIONS, PERSONAL_SECTIONS, businessSectionVisible } from "@/lib/sections";
import { useWorkspaceContext } from "@/lib/useWorkspaceContext";
import { AddTransactionDialog } from "../finance/AddTransactionDialog";
import { Icon } from "../ui/Icon";
import { AccountMenu } from "./AccountMenu";
import { ExportButton } from "./ExportButton";
import { MonthSelect } from "./MonthSelect";
import { NotificationsMenu } from "./NotificationsMenu";
import { QuickSearch, type Destination } from "./QuickSearch";
import { TransactionSearch } from "./TransactionSearch";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

/**
 * The tools at the top of a page: transaction search, month selector and export (personal finance), quick search, notifications, Add transaction
 * and the account menu. Every control acts on the workspace being viewed and is authorized again by the server. `bar` is the variant for pages
 * without the sidebar and adds the workspace switcher.
 */
export function HeaderControls({ variant = "page" }: { variant?: "page" | "bar" }) {
  const router = useRouter();
  const { ready, pathname, personal, current, currentWs } = useWorkspaceContext();
  const [adding, setAdding] = useState(false);
  const onPersonal = pathname.startsWith("/finance") || currentWs?.type === "PERSONAL";

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

  if (!ready) return null;
  const notificationsHref = currentWs ? (currentWs.type === "PERSONAL" ? "/finance/notifications" : businessSectionVisible(currentWs.role, "notifications") ? `/workspaces/${currentWs.id}/notifications` : null) : null;
  const settingsHref = currentWs?.type === "BUSINESS" && businessSectionVisible(currentWs.role, "settings") ? `/workspaces/${currentWs.id}/settings` : "/finance/regional";

  return (
    <div className="head-tools">
      {variant === "bar" && <WorkspaceSwitcher className="header-workspace" />}
      {onPersonal && personal && pathname.startsWith("/finance") && variant === "page" && <TransactionSearch />}
      {onPersonal && personal && pathname.startsWith("/finance") && variant === "page" && <MonthSelect />}
      <QuickSearch destinations={destinations} onNavigate={(href) => router.push(href)} onAction={personal ? { label: "Add a transaction", run: () => setAdding(true) } : undefined} />
      <NotificationsMenu space={current || null} allHref={notificationsHref} />
      {onPersonal && personal && pathname.startsWith("/finance") && variant === "page" && <ExportButton space={personal.id} />}
      {variant === "bar" && (
        <Link href={settingsHref} className="icon-btn" aria-label="Settings"><Icon name="settings" size={20} /></Link>
      )}
      {personal && (
        <button type="button" className="btn-primary" onClick={() => setAdding(true)} aria-haspopup="dialog">
          <Icon name="plus" /><span className="add-label">Add transaction</span><span className="visually-hidden add-sr"> (opens a form)</span>
        </button>
      )}
      <AccountMenu />
      {personal && <AddTransactionDialog open={adding} space={personal.id} onClose={() => setAdding(false)} />}
    </div>
  );
}
