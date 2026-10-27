"use client";

import { useCallback, useEffect, useState } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, insightsAccess } from "@/lib/business";
import { FinanceProvider, useFinance } from "@/lib/FinanceContext";
import type { BusinessSettings } from "@/lib/business";
import type { Workspace } from "@/lib/types";
import { Loading, Notice } from "../Notice";
import { BusinessOverview } from "./BusinessOverview";
import { BusinessSettingsPanel } from "./BusinessSettingsPanel";
import { ExpensesPanel } from "./ExpensesPanel";
import { InvoicesPanel } from "./InvoicesPanel";
import { loadLookups, NO_LOOKUPS, type Lookups } from "./lookups";
import { PartiesPanel } from "./PartiesPanel";
import { PaymentsPanel } from "./PaymentsPanel";
import { ProjectsPanel } from "./ProjectsPanel";
import { AccountingPanel } from "../accounting/AccountingPanel";
import { GstPanel } from "../gst/GstPanel";
import { RegionalPanel } from "../regional/RegionalPanel";
import { AssistantPanel } from "../assistant/AssistantPanel";
import { DocumentsPanel } from "../insights/DocumentsPanel";
import { ImportsPanel } from "../insights/ImportsPanel";
import { ReconcilePanel } from "../insights/ReconcilePanel";
import { ReportsPanel } from "../insights/ReportsPanel";

type Tab = "overview" | "invoices" | "payments" | "clients" | "vendors" | "projects" | "expenses" | "reports" | "imports" | "documents" | "reconcile" | "assistant" | "regional" | "gst" | "integrations" | "settings";

/** Business finance for one BUSINESS workspace. Every request is authorized again by the server. */
export function BusinessApp({ workspace }: { workspace: Workspace }) {
  if (workspace.type !== "BUSINESS") return <Notice kind="info">Business finance is only available in business workspaces.</Notice>;
  if (!businessAccess.any(workspace.role)) return <Notice kind="info">Your role does not have access to this workspace&apos;s finances.</Notice>;
  return (
    <FinanceProvider space={workspace.id}>
      <BusinessShell workspace={workspace} />
    </FinanceProvider>
  );
}

function BusinessShell({ workspace }: { workspace: Workspace }) {
  const finance = useFinance();
  const role = workspace.role;
  const full = businessAccess.read(role);
  const tabs: { id: Tab; label: string; show: boolean }[] = [
    { id: "overview", label: "Summary", show: full },
    { id: "invoices", label: "Invoices", show: full },
    { id: "payments", label: "Payments", show: full },
    { id: "clients", label: "Clients", show: full },
    { id: "vendors", label: "Vendors", show: full },
    { id: "projects", label: "Projects", show: full },
    { id: "expenses", label: "Expenses", show: businessAccess.expensesRead(role) },
    { id: "reports", label: "Reports", show: insightsAccess.reports(role) },
    { id: "imports", label: "Imports", show: insightsAccess.importsRead(role) },
    { id: "documents", label: "Documents", show: insightsAccess.documentsRead(role) },
    { id: "reconcile", label: "Reconcile", show: insightsAccess.importsRead(role) },
    { id: "assistant", label: "Assistant", show: insightsAccess.reports(role) },
    { id: "regional", label: "Currency & region", show: insightsAccess.reports(role) },
    { id: "gst", label: "GST", show: insightsAccess.reports(role) },
    { id: "integrations", label: "Integrations", show: businessAccess.read(role) },
    { id: "settings", label: "Settings", show: full },
  ];
  const visible = tabs.filter((t) => t.show);
  const [tab, setTab] = useState<Tab>(visible[0]!.id);
  const [lookups, setLookups] = useState<Lookups>(NO_LOOKUPS);
  const [base, setBase] = useState<{ currency: string } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);
  const changed = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const access = { clients: full, expenses: businessAccess.expensesRead(role) };
    Promise.all([
      loadLookups(finance.get, controller.signal, access),
      full ? finance.get<BusinessSettings>("/business-settings", controller.signal) : Promise.resolve(null),
    ])
      .then(([l, s]) => {
        if (controller.signal.aborted) return;
        setLookups(l);
        setBase({ currency: s?.base_currency ?? "INR" });
        setError(null);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(e);
      });
    return () => controller.abort();
  }, [finance, full, role, tick]);

  if (error) {
    return (
      <>
        <Notice>{describeError(error)}</Notice>
        <button type="button" onClick={changed}>Try again</button>
      </>
    );
  }
  if (!base) return <Loading label="Loading business finance…" />;
  const currency = base.currency;
  return (
    <>
      <div role="tablist" aria-label="Business finance sections" className="tabs">
        {visible.map((t) => (
          <button key={t.id} role="tab" type="button" aria-selected={tab === t.id} className={tab === t.id ? "tab active" : "tab"} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      <section role="tabpanel">
        {tab === "overview" && <BusinessOverview refreshKey={tick} />}
        {tab === "invoices" && <InvoicesPanel role={role} lookups={lookups} baseCurrency={currency} onChanged={changed} />}
        {tab === "payments" && <PaymentsPanel role={role} lookups={lookups} baseCurrency={currency} onChanged={changed} />}
        {tab === "clients" && <PartiesPanel kind="clients" role={role} onChanged={changed} />}
        {tab === "vendors" && <PartiesPanel kind="vendors" role={role} onChanged={changed} />}
        {tab === "projects" && <ProjectsPanel role={role} lookups={lookups} baseCurrency={currency} onChanged={changed} />}
        {tab === "expenses" && <ExpensesPanel role={role} lookups={lookups} baseCurrency={currency} onChanged={changed} />}
        {tab === "reports" && <ReportsPanel domain="BUSINESS" canExport={insightsAccess.exportReports(role)} />}
        {tab === "imports" && <ImportsPanel domain="BUSINESS" canWrite={insightsAccess.importsWrite(role)} accounts={[]} />}
        {tab === "documents" && <DocumentsPanel domain="BUSINESS" canUpload={insightsAccess.documentsUpload(role)} canManage={insightsAccess.documentsManage(role)} accounts={[]} baseCurrency={currency} />}
        {tab === "reconcile" && <ReconcilePanel canWrite={insightsAccess.importsWrite(role)} />}
        {tab === "assistant" && <AssistantPanel domain="BUSINESS" />}
        {tab === "regional" && <RegionalPanel />}
        {tab === "gst" && <GstPanel canEditRegistration={businessAccess.settingsWrite(role)} canEditDetails={businessAccess.write(role)} canExport={insightsAccess.exportReports(role)} />}
        {tab === "integrations" && <AccountingPanel />}
        {tab === "settings" && <BusinessSettingsPanel role={role} onSaved={changed} />}
      </section>
    </>
  );
}
