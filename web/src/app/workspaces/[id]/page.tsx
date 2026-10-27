"use client";

import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { Loading, Notice } from "@/components/Notice";
import { MembersPanel } from "@/components/MembersPanel";
import { InvitationsPanel } from "@/components/InvitationsPanel";
import { SettingsPanel } from "@/components/SettingsPanel";
import { BusinessApp } from "@/components/business/BusinessApp";
import { api, ApiError, describeError } from "@/lib/api";
import { canManageMembers, canViewMembers, roleLabel } from "@/lib/permissions";
import { useResource } from "@/lib/useResource";
import type { Workspace } from "@/lib/types";
import Link from "next/link";

type Tab = "overview" | "finance" | "members" | "invitations" | "settings";

function WorkspaceView({ id }: { id: string }) {
  const router = useRouter();
  const ws = useResource((signal) => api<Workspace>(`/v1/spaces/${id}`, { signal }));
  const [tab, setTab] = useState<Tab>("overview");

  if (ws.status === "loading") return <main><Loading label="Loading workspace…" /></main>;
  if (ws.status === "error") {
    const notFound = ws.error instanceof ApiError && ws.error.status === 404;
    return (
      <main>
        <Notice>{notFound ? "This workspace does not exist, or you no longer have access to it." : describeError(ws.error)}</Notice>
        <p><Link href="/workspaces">Back to your workspaces</Link></p>
      </main>
    );
  }
  const w = ws.data;
  const personal = w.type === "PERSONAL";
  const tabs: { id: Tab; label: string; show: boolean }[] = [
    { id: "overview", label: "Overview", show: true },
    { id: "finance", label: "Finance", show: !personal },
    { id: "members", label: "Members", show: !personal && canViewMembers(w.role) },
    { id: "invitations", label: "Invitations", show: !personal && canViewMembers(w.role) },
    { id: "settings", label: "Settings", show: !personal },
  ];

  return (
    <main>
      <h1>{personal ? "Personal profile" : w.name}</h1>
      <p>
        <span className="badge">{personal ? "Private to you" : `Your role: ${roleLabel(w.role)}`}</span>
      </p>
      <div role="tablist" aria-label="Workspace sections" className="tabs">
        {tabs.filter((t) => t.show).map((t) => (
          <button key={t.id} role="tab" type="button" aria-selected={tab === t.id} className={tab === t.id ? "tab active" : "tab"} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      <section role="tabpanel">
        {tab === "overview" && (
          <p>
            {personal
              ? "Your personal finances live here. Nobody else — not an employer, not a workspace admin — can see them."
              : "Invoices, payments, clients, vendors, projects, expenses and summaries are on the Finance tab."}
            {!personal && !canManageMembers(w.role) && " You do not have permission to manage members."}
          </p>
        )}
        {tab === "finance" && <BusinessApp workspace={w} />}
        {tab === "members" && <MembersPanel workspace={w} />}
        {tab === "invitations" && <InvitationsPanel workspace={w} />}
        {tab === "settings" && <SettingsPanel workspace={w} onRenamed={ws.reload} onLeft={() => router.push("/workspaces")} />}
      </section>
    </main>
  );
}

export default function WorkspacePage() {
  const params = useParams<{ id: string }>();
  return (
    <AuthGate>
      <WorkspaceView id={params.id} />
    </AuthGate>
  );
}
