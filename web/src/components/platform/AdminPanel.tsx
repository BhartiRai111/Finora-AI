"use client";

import Link from "next/link";
import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { SEVERITY_TEXT, STATE_TEXT, humanizeType, type AlertPage, type Integration, type Subscription } from "@/lib/platform";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { AdminOverviewPanel } from "./IntegrationRegistry";
import { BillingRecords } from "./SubscriptionPanel";

/**
 * Administration of THIS workspace: one place to see its plan, integration health, open alerts, notification count and billing history,
 * with links to the screens that act on them. Everything is read from the server; nothing here changes data, and nothing here is
 * platform-wide administration.
 */
export function AdminPanel() {
  const finance = useFinance();
  const base = `/workspaces/${finance.space}`;
  return (
    <>
      <AdminOverviewPanel />
      <PlanSummary base={base} />
      <IntegrationHealth base={base} />
      <OpenAlerts base={base} />
      <section aria-labelledby="adm-billing-title">
        <h2 id="adm-billing-title">Billing history</h2>
        <p className="muted">Records for this workspace. <Link href={`${base}/plan`}>Plan and billing</Link> has the contact details.</p>
        <BillingRecords pageSize={10} />
      </section>
      <PlatformBoundary />
    </>
  );
}

function PlanSummary({ base }: { base: string }) {
  const finance = useFinance();
  const sub = useResource((signal) => finance.get<Subscription>("/finance-subscription", signal));
  return (
    <section aria-labelledby="adm-plan-title">
      <h2 id="adm-plan-title">Plan and subscription</h2>
      {sub.status === "loading" && <Loading label="Loading the plan…" />}
      {sub.status === "error" && <><Notice>{describeError(sub.error)}</Notice><button type="button" onClick={sub.reload}>Try again</button></>}
      {sub.status === "ready" && (
        <>
          <p>
            <strong>{sub.data.plan.name}</strong>: {humanizeType(sub.data.status)}.{sub.data.plan.provisional ? " This plan is provisional and has no price." : ""}
            {sub.data.implicit_default ? " The workspace has not chosen a plan, so the default applies." : ""}
          </p>
          <ul>
            {sub.data.effective.map((e) => (
              <li key={e.key}>{humanizeType(e.key)}: {e.enabled ? "available" : `not available${e.reason ? ` (${e.reason})` : ""}`}{e.limit !== null ? `, limit ${e.limit}` : ""}</li>
            ))}
          </ul>
          <p><Link href={`${base}/plan`}>Manage the plan</Link> (Owner only)</p>
        </>
      )}
    </section>
  );
}

function IntegrationHealth({ base }: { base: string }) {
  const finance = useFinance();
  const reg = useResource((signal) => finance.get<{ items: Integration[] }>("/finance-integrations", signal));
  return (
    <section aria-labelledby="adm-int-title">
      <h2 id="adm-int-title">Integration health</h2>
      {reg.status === "loading" && <Loading label="Loading integrations…" />}
      {reg.status === "error" && <><Notice>{describeError(reg.error)}</Notice><button type="button" onClick={reg.reload}>Try again</button></>}
      {reg.status === "ready" && (
        <table>
          <caption>State of each external service, from configuration and real records</caption>
          <thead><tr><th scope="col">Service</th><th scope="col">State</th><th scope="col">What it means</th></tr></thead>
          <tbody>
            {reg.data.items.map((i) => (
              <tr key={i.key}>
                <th scope="row">{i.name}</th>
                <td>{STATE_TEXT[i.state]}</td>
                <td>{i.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p><Link href={`${base}/integrations`}>Integration details and synchronisation errors</Link></p>
    </section>
  );
}

function OpenAlerts({ base }: { base: string }) {
  const finance = useFinance();
  const alerts = useResource((signal) => finance.get<AlertPage>("/finance-alerts?status=OPEN&limit=5", signal));
  return (
    <section aria-labelledby="adm-alert-title">
      <h2 id="adm-alert-title">Open alerts</h2>
      {alerts.status === "loading" && <Loading label="Loading alerts…" />}
      {alerts.status === "error" && <><Notice>{describeError(alerts.error)}</Notice><button type="button" onClick={alerts.reload}>Try again</button></>}
      {alerts.status === "ready" && alerts.data.items.length === 0 && <p>No alerts are open. Alerts are checked only when someone asks, so this is not a guarantee that nothing needs attention.</p>}
      {alerts.status === "ready" && alerts.data.items.length > 0 && (
        <>
          <ul>{alerts.data.items.map((a) => <li key={a.id}>{a.title} <span className="muted">({SEVERITY_TEXT[a.severity]}, {humanizeType(a.alert_type)}, {a.generated_at.slice(0, 10)})</span></li>)}</ul>
          {alerts.data.total > alerts.data.items.length && <p className="muted">Showing {alerts.data.items.length} of {alerts.data.total} open alerts.</p>}
        </>
      )}
      <p><Link href={`${base}/alerts`}>All alerts and their settings</Link> · <Link href={`${base}/notifications`}>Notifications</Link></p>
    </section>
  );
}

/** What does not exist, said once and plainly, instead of disabled controls that suggest it might. */
function PlatformBoundary() {
  return (
    <section aria-labelledby="adm-platform-title">
      <h2 id="adm-platform-title">Not available: platform-wide administration</h2>
      <p>
        This page administers one workspace. There is no platform administration: no staff accounts, no cross-workspace views and no support access to your data.
        It cannot be built safely until a platform-staff identity and authorization model is approved: how staff sign in, which roles exist, how access is limited and
        audited, and how it stays separate from workspace roles. Until then nothing in Finora lets anyone bypass your workspace permissions.
      </p>
    </section>
  );
}
