"use client";

import { useParams, useRouter } from "next/navigation";
import { AuthGate } from "@/components/AuthGate";
import { Loading } from "@/components/Notice";
import { FinanceApp } from "@/components/finance/FinanceApp";
import { SidebarSummary } from "@/components/finance/SidebarSummary";
import { AppShell } from "@/components/shell/AppShell";
import { HeaderControls } from "@/components/shell/HeaderControls";
import { ShellRail } from "@/components/shell/ShellRail";
import { SidebarProfile } from "@/components/shell/SidebarProfile";
import { TopBar } from "@/components/shell/TopBar";
import { EmptyState } from "@/components/ui/States";
import { FinanceProvider } from "@/lib/FinanceContext";
import { MonthProvider } from "@/lib/month";
import { PERSONAL_SECTIONS, toNav } from "@/lib/sections";
import { useWorkspaceContext } from "@/lib/useWorkspaceContext";

const nav = toNav(PERSONAL_SECTIONS, (id) => `/finance/${id}`);
const footLinks: { label: string; href: string; icon: "settings" | "help" }[] = [];

function FinanceScreen() {
  const params = useParams<{ section?: string[] }>();
  const router = useRouter();
  const { ready, personal } = useWorkspaceContext();
  const parts = params.section ?? [];
  const id = parts[0] ?? "overview";
  const section = parts.length <= 1 ? PERSONAL_SECTIONS.find((s) => s.id === id) : undefined;
  if (!ready) return <main id="main"><Loading label="Loading your personal profile…" /></main>;
  const shell = (extra: React.ReactNode, body: React.ReactNode) => (
    <AppShell contextLabel="Personal finance" badge="Private to you" eyebrow={id === "overview" ? "Personal finance workspace" : "Personal finance"} nav={nav} currentId={section ? id : ""}
      title={section?.title ?? "Page not found"} description={section?.description} switcher={<SidebarProfile />} rail={<ShellRail />} topbar={<TopBar trail={["Finance", section?.title ?? "Page not found"]} />} sidebarExtra={extra} footLinks={footLinks} actions={<HeaderControls />}>
      {body}
    </AppShell>
  );
  const body = section ? <FinanceApp space={personal?.id} section={id} onSectionChange={(next) => router.push(`/finance/${next}`)} /> : <EmptyState title="This page does not exist">Choose a section from the menu.</EmptyState>;
  if (!personal) return shell(null, body);
  return <FinanceProvider space={personal.id}>{shell(<SidebarSummary />, body)}</FinanceProvider>;
}

export default function FinancePage() {
  return (
    <AuthGate>
      <MonthProvider>
        <FinanceScreen />
      </MonthProvider>
    </AuthGate>
  );
}
