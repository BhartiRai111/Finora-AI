"use client";

import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import type { AccountingStatus } from "@/lib/regional";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

/** Accounting integration state. With no approved provider it says so and offers no connect button; it never shows a connected status that is not real. */
export function AccountingPanel() {
  const finance = useFinance();
  const status = useResource((signal) => finance.get<AccountingStatus>("/finance-accounting", signal));
  if (status.status === "loading") return <Loading label="Checking accounting integrations…" />;
  if (status.status === "error") return <><Notice>{describeError(status.error)}</Notice><button type="button" onClick={status.reload}>Try again</button></>;
  const s = status.data;
  return (
    <section aria-labelledby="acct-title">
      <h2 id="acct-title">Accounting integrations</h2>
      <Notice kind="info">{s.message}</Notice>
      <ul>
        <li>Provider: {s.provider.status === "NOT_CONFIGURED" ? "none configured" : s.provider.key}</li>
        <li>Connect with OAuth: {s.capabilities.oauth_connect ? "available" : "not available"}</li>
        <li>Webhooks: {s.capabilities.webhooks ? "enabled" : "not enabled"}</li>
        <li>Synchronisation: {s.capabilities.sync ? "available" : "not available"}</li>
        <li>Secure token storage: {s.token_vault.available ? "configured" : "not configured"}</li>
      </ul>
      {s.connections.length === 0 ? <p>No accounting system is connected.</p> : (
        <table>
          <caption>Connections</caption>
          <thead><tr><th scope="col">Provider</th><th scope="col">Status</th></tr></thead>
          <tbody>{s.connections.map((c) => <tr key={c.provider}><td>{c.provider}</td><td>{c.status}</td></tr>)}</tbody>
        </table>
      )}
      <h3>Decisions still needed before an integration can exist</h3>
      <ul>{s.pending_decisions.map((d) => <li key={d}>{d}</li>)}</ul>
    </section>
  );
}
