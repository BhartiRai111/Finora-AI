"use client";

import { useParams, useRouter } from "next/navigation";
import { AuthGate } from "@/components/AuthGate";
import { AppShell } from "@/components/shell/AppShell";
import { EmptyState } from "@/components/ui/States";
import { FinanceApp } from "@/components/finance/FinanceApp";
import { PERSONAL_SECTIONS, toNav } from "@/lib/sections";

const nav = toNav(PERSONAL_SECTIONS, (id) => `/finance/${id}`);

export default function FinancePage() {
  const params = useParams<{ section?: string[] }>();
  const router = useRouter();
  const parts = params.section ?? [];
  const id = parts[0] ?? "overview";
  const section = parts.length <= 1 ? PERSONAL_SECTIONS.find((s) => s.id === id) : undefined;
  return (
    <AuthGate>
      <AppShell contextLabel="Personal finance" badge="Private to you" nav={nav} currentId={section ? id : ""} title={section?.title ?? "Page not found"} description={section?.description}>
        {section ? <FinanceApp section={id} onSectionChange={(next) => router.push(`/finance/${next}`)} /> : <EmptyState title="This page does not exist">Choose a section from the menu.</EmptyState>}
      </AppShell>
    </AuthGate>
  );
}
