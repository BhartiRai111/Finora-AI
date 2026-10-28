"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import type { Account, Category } from "@/lib/finance";
import { FinanceProvider, useFinance } from "@/lib/FinanceContext";
import type { Workspace } from "@/lib/types";
import { PERSONAL_SECTIONS } from "@/lib/sections";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { EmptyState } from "../ui/States";
import { api } from "@/lib/api";
import { AccountsPanel } from "./AccountsPanel";
import { CalendarPlanner } from "./CalendarPlanner";
import { BudgetsPanel } from "./BudgetsPanel";
import { CategoriesPanel } from "./CategoriesPanel";
import { CommitmentsPanel } from "./CommitmentsPanel";
import { GoalsPanel } from "./GoalsPanel";
import { Dashboard } from "./Dashboard";
import { RecurringPanel } from "./RecurringPanel";
import { TransactionsPanel } from "./TransactionsPanel";
import { AlertsPanel } from "../platform/AlertsPanel";
import { IntegrationRegistry } from "../platform/IntegrationRegistry";
import { NotificationStrip, NotificationsPanel } from "../platform/NotificationsPanel";
import { SubscriptionPanel } from "../platform/SubscriptionPanel";
import { RegionalPanel } from "../regional/RegionalPanel";
import { AssistantPanel } from "../assistant/AssistantPanel";
import { DocumentsPanel } from "../insights/DocumentsPanel";
import { ImportsPanel } from "../insights/ImportsPanel";
import { ReconcilePanel } from "../insights/ReconcilePanel";
import { ReportsPanel } from "../insights/ReportsPanel";

const TABS = PERSONAL_SECTIONS.map((x) => [x.id, x.label] as const);
type TabId = string;

/**
 * Finds the signed-in user's personal workspace; personal finance is private to it. Used on its own it shows its own tabs; given a
 * `section` (the application shell does this) it renders only that section and navigation is the shell's job.
 */
export function FinanceApp({ section, onSectionChange, space }: { section?: string; onSectionChange?: (id: string) => void; space?: string } = {}) {
  const workspaces = useResource((signal) => (space ? Promise.resolve({ items: [] as Workspace[] }) : api<{ items: Workspace[] }>("/v1/workspaces", { signal })));
  if (space) return <FinanceProvider space={space}><FinanceShell section={section} onSectionChange={onSectionChange} /></FinanceProvider>;
  if (workspaces.status === "loading") return <Loading label="Loading your personal profile…" />;
  if (workspaces.status === "error") {
    return (
      <>
        <Notice>{describeError(workspaces.error)}</Notice>
        <button type="button" onClick={workspaces.reload}>Try again</button>
      </>
    );
  }
  const personal = workspaces.data.items.find((w) => w.type === "PERSONAL");
  if (!personal) return <Notice>You have no personal profile yet. Personal finance is only available in a personal profile.</Notice>;
  return (
    <FinanceProvider space={personal.id}>
      <FinanceShell section={section} onSectionChange={onSectionChange} />
    </FinanceProvider>
  );
}

function FinanceShell({ section, onSectionChange }: { section?: string; onSectionChange?: (id: string) => void }) {
  const finance = useFinance();
  const [localTab, setLocalTab] = useState<TabId>("overview");
  const controlled = section !== undefined;
  const known = TABS.some(([id]) => id === section);
  const tab: TabId = controlled ? (known ? section : "overview") : localTab;
  const setTab = (id: TabId) => (onSectionChange ? onSectionChange(id) : setLocalTab(id));
  const [unreadKey, setUnreadKey] = useState(0);
  const base = useResource(async (signal) => {
    const [a, c] = await Promise.all([
      finance.get<{ items: Account[] }>("/accounts?include_archived=true", signal),
      finance.get<{ items: Category[] }>("/categories?include_archived=true", signal),
    ]);
    return { accounts: a.items, categories: c.items };
  });

  if (base.status === "loading") return <Loading label="Loading your finances…" />;
  if (base.status === "error") {
    return (
      <>
        <Notice>{describeError(base.error)}</Notice>
        <button type="button" onClick={base.reload}>Try again</button>
      </>
    );
  }
  if (controlled && !known) return <EmptyState title="This page does not exist">Choose a section from the menu.</EmptyState>;
  const { accounts, categories } = base.data;
  const open = accounts.filter((a) => !a.archived);
  const needsAccount = ["transactions", "recurring"].includes(tab);
  return (
    <>
      <NotificationStrip onOpen={() => setTab("notifications")} refreshKey={unreadKey} />
      {!controlled && (
        <div role="tablist" aria-label="Finance sections" className="tabs">
          {TABS.map(([id, label]) => (
            <button key={id} role="tab" type="button" aria-selected={tab === id} className={tab === id ? "tab active" : "tab"} onClick={() => setTab(id)}>
              {label}
            </button>
          ))}
        </div>
      )}
      <section role={controlled ? undefined : "tabpanel"}>
        {needsAccount && open.length === 0 && tab !== "bills" && tab !== "subscriptions" && (
          <Notice kind="info">Add an account on the Accounts tab before recording transactions.</Notice>
        )}
        {tab === "overview" && <Dashboard onChanged={base.reload} />}
        {tab === "calendar" && <CalendarPlanner />}
        {tab === "accounts" && <AccountsPanel accounts={accounts} onChanged={base.reload} />}
        {tab === "transactions" && open.length > 0 && <TransactionsPanel accounts={accounts} categories={categories} onChanged={base.reload} />}
        {tab === "recurring" && open.length > 0 && <RecurringPanel accounts={accounts} />}
        {tab === "budgets" && <BudgetsPanel categories={categories} />}
        {tab === "goals" && <GoalsPanel />}
        {tab === "bills" && <CommitmentsPanel kind="BILL" accounts={accounts} />}
        {tab === "subscriptions" && <CommitmentsPanel kind="SUBSCRIPTION" accounts={accounts} />}
        {tab === "categories" && <CategoriesPanel categories={categories} onChanged={base.reload} />}
        {tab === "reports" && <ReportsPanel domain="PERSONAL" canExport />}
        {tab === "imports" && <ImportsPanel domain="PERSONAL" canWrite accounts={accounts} />}
        {tab === "documents" && <DocumentsPanel domain="PERSONAL" canUpload canManage accounts={accounts} baseCurrency="INR" />}
        {tab === "reconcile" && <ReconcilePanel canWrite />}
        {tab === "assistant" && <AssistantPanel domain="PERSONAL" />}
        {tab === "regional" && <RegionalPanel />}
        {tab === "notifications" && <NotificationsPanel onChanged={() => setUnreadKey((n) => n + 1)} />}
        {tab === "alerts" && <AlertsPanel canEditSettings />}
        {tab === "plan" && <SubscriptionPanel isOwner canSeeAdmin />}
        {tab === "integrations" && <IntegrationRegistry />}
      </section>
    </>
  );
}
