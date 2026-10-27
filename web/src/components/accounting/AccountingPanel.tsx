"use client";

import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import type { AccountingStatus } from "@/lib/regional";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

/** Accounting integration state. With no approved provider it says so and offers no connect button; it never shows a connected status that is not real. */
type SyncRun = { id: string; provider: string; direction: string; status: string; attempts: number; records_ok: number; records_failed: number; error_code: string | null; error_message: string | null; started_at: string; finished_at: string | null };

export function AccountingPanel() {
  const finance = useFinance();
  const status = useResource((signal) => finance.get<AccountingStatus>("/finance-accounting", signal));
  const runs = useResource((signal) => finance.get<{ items: SyncRun[] }>("/finance-accounting/sync-runs", signal).catch(() => null));
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
      <h3>Synchronisation history</h3>
      {runs.status === "ready" && runs.data && runs.data.items.length === 0 && <p>No synchronisation has run.</p>}
      {runs.status === "ready" && runs.data && runs.data.items.length > 0 && (
        <table>
          <caption>Most recent synchronisation runs</caption>
          <thead><tr><th scope="col">Started</th><th scope="col">Provider</th><th scope="col">Direction</th><th scope="col">Result</th><th scope="col">Records ok / failed</th></tr></thead>
          <tbody>{runs.data.items.map((r) => <tr key={r.id}><td>{r.started_at.slice(0, 16).replace("T", " ")}</td><td>{r.provider}</td><td>{r.direction.toLowerCase()}</td><td>{r.status.toLowerCase()}{r.error_code ? ` (${r.error_code})` : ""}</td><td>{r.records_ok} / {r.records_failed}</td></tr>)}</tbody>
        </table>
      )}
      {runs.status === "ready" && runs.data === null && <p className="muted">The synchronisation history could not be loaded.</p>}
      <h3>Decisions still needed before an integration can exist</h3>
      <ul>{s.pending_decisions.map((d) => <li key={d}>{d}</li>)}</ul>
    </section>
  );
}
