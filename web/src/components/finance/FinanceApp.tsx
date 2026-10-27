"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import type { Account, Category } from "@/lib/finance";
import { FinanceProvider, useFinance } from "@/lib/FinanceContext";
import type { Workspace } from "@/lib/types";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { api } from "@/lib/api";
import { AccountsPanel } from "./AccountsPanel";
import { BudgetsPanel } from "./BudgetsPanel";
import { CategoriesPanel } from "./CategoriesPanel";
import { CommitmentsPanel } from "./CommitmentsPanel";
import { GoalsPanel } from "./GoalsPanel";
import { OverviewPanel } from "./OverviewPanel";
import { RecurringPanel } from "./RecurringPanel";
import { TransactionsPanel } from "./TransactionsPanel";
import { AssistantPanel } from "../assistant/AssistantPanel";
import { DocumentsPanel } from "../insights/DocumentsPanel";
import { ImportsPanel } from "../insights/ImportsPanel";
import { ReconcilePanel } from "../insights/ReconcilePanel";
import { ReportsPanel } from "../insights/ReportsPanel";

const TABS = [
  ["overview", "Overview"],
  ["accounts", "Accounts"],
  ["transactions", "Transactions"],
  ["recurring", "Recurring"],
  ["budgets", "Budgets"],
  ["goals", "Goals"],
  ["bills", "Bills"],
  ["subscriptions", "Subscriptions"],
  ["categories", "Categories"],
  ["reports", "Reports"],
  ["imports", "Imports"],
  ["documents", "Documents"],
  ["reconcile", "Reconcile"],
  ["assistant", "Assistant"],
] as const;

type TabId = (typeof TABS)[number][0];

/** Finds the signed-in user's personal workspace; personal finance is private to it. */
export function FinanceApp() {
  const workspaces = useResource((signal) => api<{ items: Workspace[] }>("/v1/workspaces", { signal }));
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
      <FinanceShell />
    </FinanceProvider>
  );
}

function FinanceShell() {
  const finance = useFinance();
  const [tab, setTab] = useState<TabId>("overview");
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
  const { accounts, categories } = base.data;
  const open = accounts.filter((a) => !a.archived);
  const needsAccount = ["transactions", "recurring"].includes(tab);
  return (
    <>
      <div role="tablist" aria-label="Finance sections" className="tabs">
        {TABS.map(([id, label]) => (
          <button key={id} role="tab" type="button" aria-selected={tab === id} className={tab === id ? "tab active" : "tab"} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      <section role="tabpanel">
        {needsAccount && open.length === 0 && tab !== "bills" && tab !== "subscriptions" && (
          <Notice kind="info">Add an account on the Accounts tab before recording transactions.</Notice>
        )}
        {tab === "overview" && <OverviewPanel onChanged={base.reload} />}
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
      </section>
    </>
  );
}
