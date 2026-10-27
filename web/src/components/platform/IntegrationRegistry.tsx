"use client";

import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { STATE_TEXT, type AdminOverview, type Integration } from "@/lib/platform";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

/** Every external dependency and its REAL state. An unconfigured provider is shown as not configured; nothing is shown as connected unless a connection exists. */
export function IntegrationRegistry() {
  const finance = useFinance();
  const reg = useResource((signal) => finance.get<{ items: Integration[] }>("/finance-integrations", signal));
  const errors = useResource((signal) => finance.get<{ items: { run_id: string; provider: string; status: string; error_code: string | null; error_message: string | null; finished_at: string }[] }>("/finance-integrations/accounting/errors", signal));
  if (reg.status === "loading") return <Loading label="Loading integrations…" />;
  if (reg.status === "error") return <><Notice>{describeError(reg.error)}</Notice><button type="button" onClick={reg.reload}>Try again</button></>;
  return (
    <section aria-labelledby="int-title">
      <h2 id="int-title">Integration status</h2>
      <p className="muted">States come from configuration and real records. No live connection check is made here, and no provider is simulated.</p>
      <ul className="list">
        {reg.data.items.map((i) => (
          <li key={i.key}>
            <strong>{i.name}</strong>: <span data-state={i.state}>{STATE_TEXT[i.state]}</span>
            <div>{i.message}</div>
            {i.key === "accounting" && i.state !== "NOT_CONFIGURED" && (
              <div className="muted">Last successful operation: {(i.health.last_successful_operation_at as string | null) ?? "none"}. Latest operation: {String(i.health.last_operation_status ?? "none")}.</div>
            )}
            {i.pending_decisions.length > 0 && (
              <details><summary>Decisions needed</summary><ul>{i.pending_decisions.map((d) => <li key={d}>{d}</li>)}</ul></details>
            )}
          </li>
        ))}
      </ul>
      <h3>Synchronisation errors</h3>
      {errors.status === "loading" && <Loading label="Loading error history…" />}
      {errors.status === "error" && <Notice>{describeError(errors.error)}</Notice>}
      {errors.status === "ready" && errors.data.items.length === 0 && <p>No synchronisation has failed.</p>}
      {errors.status === "ready" && errors.data.items.length > 0 && (
        <ul>{errors.data.items.map((e) => <li key={e.run_id}>{e.finished_at.slice(0, 10)} · {e.provider} · {e.status.toLowerCase()} · {e.error_code ?? "no code"}{e.error_message ? `: ${e.error_message}` : ""}</li>)}</ul>
      )}
    </section>
  );
}

/** Administration of THIS workspace only; platform-wide administration does not exist yet. */
export function AdminOverviewPanel() {
  const finance = useFinance();
  const o = useResource((signal) => finance.get<AdminOverview>("/finance-admin/overview", signal));
  if (o.status === "loading") return <Loading label="Loading the workspace overview…" />;
  if (o.status === "error") return <><Notice>{describeError(o.error)}</Notice><button type="button" onClick={o.reload}>Try again</button></>;
  const d = o.data;
  const entries = (m: Record<string, number>) => Object.entries(m).map(([k, v]) => `${k.toLowerCase().replace(/_/g, " ")}: ${v}`).join(", ") || "none";
  return (
    <section aria-labelledby="adm-title">
      <h2 id="adm-title">Workspace administration</h2>
      <Notice kind="info">{d.note}</Notice>
      <ul>
        <li>Plan: {d.subscription.plan_name} ({d.subscription.status.toLowerCase().replace("_", " ")}){d.subscription.provisional ? ", provisional" : ""}</li>
        <li>Integrations by state: {entries(d.integrations_by_state)}</li>
        <li>Open alerts by severity: {entries(d.open_alerts_by_severity)}</li>
        <li>Your unread notifications: {d.unread_notifications}</li>
        <li>Billing provider: {d.billing.provider_status.toLowerCase().replace("_", " ")}; open records {d.billing.open_records}; failed payments {d.billing.failed_payment_records}</li>
      </ul>
    </section>
  );
}
