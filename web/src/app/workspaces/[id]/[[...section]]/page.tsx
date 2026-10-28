"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";
import { AuthGate } from "@/components/AuthGate";
import { InvitationsPanel } from "@/components/InvitationsPanel";
import { MembersPanel } from "@/components/MembersPanel";
import { Loading, Notice } from "@/components/Notice";
import { SettingsPanel } from "@/components/SettingsPanel";
import { BusinessApp } from "@/components/business/BusinessApp";
import { AppShell } from "@/components/shell/AppShell";
import { HeaderControls } from "@/components/shell/HeaderControls";
import { WorkspaceSwitcher } from "@/components/shell/WorkspaceSwitcher";
import { AccessDenied, EmptyState } from "@/components/ui/States";
import { api, ApiError, describeError } from "@/lib/api";
import { roleLabel } from "@/lib/permissions";
import { BUSINESS_SECTIONS, businessSectionVisible, businessSections, toNav } from "@/lib/sections";
import { useResource } from "@/lib/useResource";
import type { Workspace } from "@/lib/types";

function WorkspaceView({ id, requested }: { id: string; requested: string[] }) {
  const router = useRouter();
  const ws = useResource((signal) => api<Workspace>(`/v1/spaces/${id}`, { signal }));
  const personal = ws.status === "ready" && ws.data.type === "PERSONAL";

  // Personal finance has its own area; a personal profile has no workspace administration.
  useEffect(() => {
    if (personal) router.replace("/finance");
  }, [personal, router]);

  if (ws.status === "loading" || personal) return <main id="main"><Loading label="Loading workspace…" /></main>;
  if (ws.status === "error") {
    const notFound = ws.error instanceof ApiError && ws.error.status === 404;
    return (
      <main id="main">
        <Notice>{notFound ? "This workspace does not exist, or you no longer have access to it." : describeError(ws.error)}</Notice>
        {!notFound && <button type="button" onClick={ws.reload}>Try again</button>}
        <p><Link href="/workspaces">Back to your workspaces</Link></p>
      </main>
    );
  }
  const w = ws.data;
  const visible = businessSections(w);
  const sectionId = requested.length === 0 ? (visible[0]?.id ?? "workspace") : requested[0]!;
  const section = requested.length <= 1 ? BUSINESS_SECTIONS.find((s) => s.id === sectionId) : undefined;
  const allowed = section !== undefined && businessSectionVisible(w.role, section.id);
  const nav = toNav(visible, (s) => `/workspaces/${w.id}/${s}`);
  const go = (next: string) => router.push(`/workspaces/${w.id}/${next}`);

  let body;
  if (!section) body = <EmptyState title="This page does not exist">Choose a section from the menu.</EmptyState>;
  else if (!allowed) body = <AccessDenied />;
  else if (section.id === "members") body = <MembersPanel workspace={w} />;
  else if (section.id === "invitations") body = <InvitationsPanel workspace={w} />;
  else if (section.id === "workspace") body = <SettingsPanel workspace={w} onRenamed={ws.reload} onLeft={() => router.push("/workspaces")} />;
  else body = <BusinessApp workspace={w} section={section.id} onSectionChange={go} />;

  return (
    <AppShell contextLabel={w.name} badge={`Your role: ${roleLabel(w.role)}`} nav={nav} currentId={section && allowed ? section.id : ""} title={!section ? "Page not found" : allowed ? section.title : "Access denied"} description={allowed ? section?.description : undefined}
      eyebrow="Business workspace" switcher={<WorkspaceSwitcher />} actions={<HeaderControls />}
      footLinks={[{ label: "Help Center", href: "/help", icon: "help" }]}>
      {body}
    </AppShell>
  );
}

export default function WorkspacePage() {
  const params = useParams<{ id: string; section?: string[] }>();
  return (
    <AuthGate>
      <WorkspaceView id={params.id} requested={params.section ?? []} />
    </AuthGate>
  );
}
