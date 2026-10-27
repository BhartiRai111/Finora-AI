"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { SEVERITY_TEXT, humanizeType, type AlertPage, type AlertSetting, type EvaluationResult } from "@/lib/platform";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const PAGE = 20;

function SettingRow({ s, canEdit, onSaved }: { s: AlertSetting; canEdit: boolean; onSaved: () => void }) {
  const finance = useFinance();
  const [enabled, setEnabled] = useState(s.enabled);
  const [threshold, setThreshold] = useState(s.threshold ?? "");
  const [days, setDays] = useState(s.days === null ? "" : String(s.days));
  const [currency, setCurrency] = useState(s.currency ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const isLow = s.alert_type === "LOW_CASH";

  async function save() {
    setSaved(false);
    if (threshold !== "" && !/^\d+(\.\d{1,4})?$/.test(threshold)) return setError("Enter the threshold as a positive number.");
    if (days !== "" && (!/^\d+$/.test(days) || Number(days) < 1 || Number(days) > 365)) return setError("Days must be a whole number from 1 to 365.");
    if (isLow && enabled && (threshold === "" || !/^[A-Z]{3}$/.test(currency))) return setError("Low-cash alerts need an explicit threshold and a currency such as INR. Nothing is assumed.");
    setError(null);
    try {
      await finance.send("PUT", `/finance-alerts/settings/${s.alert_type}`, {
        enabled,
        threshold: s.threshold_meaning && threshold !== "" ? threshold : undefined,
        days: s.days !== null && days !== "" ? Number(days) : undefined,
        currency: isLow && currency ? currency : undefined,
      });
      setSaved(true);
      onSaved();
    } catch (e) {
      setError(describeError(e));
    }
  }

  const id = s.alert_type.toLowerCase();
  return (
    <li>
      <strong>{humanizeType(s.alert_type)}</strong> <span className="muted">({s.severity}; {s.is_default ? "default settings" : "your settings"})</span>
      <div className="muted">Trigger: {s.trigger}.</div>
      {error && <Notice>{error}</Notice>}
      {saved && <Notice kind="success">Saved.</Notice>}
      <div className="filters">
        <label><input type="checkbox" checked={enabled} disabled={!canEdit} onChange={(e) => setEnabled(e.target.checked)} /> Enabled</label>
        {s.threshold_meaning && <div className="column"><label htmlFor={`${id}-th`}>Threshold ({s.threshold_meaning})</label><input id={`${id}-th`} inputMode="decimal" value={threshold} disabled={!canEdit} onChange={(e) => setThreshold(e.target.value)} /></div>}
        {s.days !== null && <div className="column"><label htmlFor={`${id}-days`}>Days</label><input id={`${id}-days`} inputMode="numeric" value={days} disabled={!canEdit} onChange={(e) => setDays(e.target.value)} /></div>}
        {isLow && <div className="column"><label htmlFor={`${id}-cur`}>Currency</label><input id={`${id}-cur`} maxLength={3} value={currency} disabled={!canEdit} onChange={(e) => setCurrency(e.target.value.toUpperCase())} /></div>}
        {canEdit && <button type="button" onClick={() => void save()} aria-label={`Save ${humanizeType(s.alert_type)} settings`}>Save</button>}
      </div>
    </li>
  );
}

/** Alert history, details, evaluation on demand and the supported settings. Alerts only inform; they never change a record or move money. */
export function AlertsPanel({ canEditSettings }: { canEditSettings: boolean }) {
  const finance = useFinance();
  const [status, setStatus] = useState("");
  const [offset, setOffset] = useState(0);
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<{ s: "loading" } | { s: "error"; m: string } | { s: "ready"; page: AlertPage }>({ s: "loading" });
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<EvaluationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const settings = useResource((signal) => finance.get<{ items: AlertSetting[] }>("/finance-alerts/settings", signal));

  useEffect(() => {
    const controller = new AbortController();
    const q = new URLSearchParams({ limit: String(PAGE), offset: String(offset) });
    if (status) q.set("status", status);
    finance.get<AlertPage>(`/finance-alerts?${q.toString()}`, controller.signal).then((page) => {
      if (!controller.signal.aborted) setState({ s: "ready", page });
    }).catch((e: unknown) => {
      if (!controller.signal.aborted) setState({ s: "error", m: describeError(e) });
    });
    return () => controller.abort();
  }, [finance, status, offset, tick]);

  async function evaluate() {
    setBusy(true);
    setError(null);
    try {
      setResult(await finance.send<EvaluationResult>("POST", "/finance-alerts/evaluate", {}));
      setTick((n) => n + 1);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function act(id: string, kind: "acknowledge" | "resolve") {
    let reason: string | undefined;
    if (kind === "resolve") {
      reason = window.prompt("Why is this alert resolved? (optional)") ?? undefined;
    }
    setError(null);
    try {
      await finance.send("POST", `/finance-alerts/${id}/${kind}`, kind === "resolve" ? { reason } : {});
      setTick((n) => n + 1);
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <section aria-labelledby="alerts-title">
      <h2 id="alerts-title">Alerts</h2>
      <p className="muted">Alerts come from fixed rules over your recorded data, not from an AI. They are checked only when you ask, so nothing is monitored between checks, and they only inform: nothing is changed, paid or filed.</p>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="group" aria-label="Alert filters">
        <div className="column">
          <label htmlFor="al-status">Status</label>
          <select id="al-status" value={status} onChange={(e) => { setStatus(e.target.value); setOffset(0); }}>
            <option value="">All</option><option value="OPEN">Open</option><option value="ACKNOWLEDGED">Acknowledged</option><option value="RESOLVED">Resolved</option>
          </select>
        </div>
        <button type="button" disabled={busy} onClick={() => void evaluate()}>{busy ? "Checking…" : "Check for alerts now"}</button>
      </div>
      {result && (
        <Notice kind="success">
          Checked on {result.evaluated_on}: {Object.values(result.created).reduce((a, b) => a + b, 0)} new, {Object.values(result.resolved).reduce((a, b) => a + b, 0)} resolved.
          {result.skipped.length > 0 && ` Not evaluated: ${result.skipped.map((s) => `${humanizeType(s.type)} (${s.reason})`).join("; ")}.`} {result.note}
        </Notice>
      )}
      {state.s === "loading" && <Loading label="Loading alerts…" />}
      {state.s === "error" && <><Notice>{state.m}</Notice><button type="button" onClick={() => setTick((n) => n + 1)}>Try again</button></>}
      {state.s === "ready" && (
        <>
          <p role="status">{state.page.open} open alert{state.page.open === 1 ? "" : "s"}; {state.page.total} in this view.</p>
          {state.page.items.length === 0 && <p>No alerts. That means no rule has fired since it was last checked, not that nothing needs attention.</p>}
          <ul className="list">
            {state.page.items.map((a) => (
              <li key={a.id}>
                <strong>{a.title}</strong> <span className="muted">({SEVERITY_TEXT[a.severity]} · {humanizeType(a.alert_type)} · {a.status.toLowerCase()} · generated {a.generated_at.slice(0, 10)})</span>
                <details>
                  <summary>Why this alert</summary>
                  <dl>{Object.entries(a.details).map(([k, v]) => <div key={k}><dt>{humanizeType(k)}</dt><dd>{String(v)}</dd></div>)}</dl>
                  {a.window_start && <p className="muted">Window: {a.window_start} to {a.window_end}.</p>}
                  {a.resolved_reason && <p className="muted">Resolved: {a.resolved_reason}</p>}
                </details>
                {a.status === "OPEN" && <button type="button" onClick={() => void act(a.id, "acknowledge")} aria-label={`Acknowledge ${a.title}`}>Acknowledge</button>}
                {a.status !== "RESOLVED" && <button type="button" onClick={() => void act(a.id, "resolve")} aria-label={`Resolve ${a.title}`}>Resolve</button>}
              </li>
            ))}
          </ul>
          <div className="filters" role="group" aria-label="Pages">
            <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
            <span>{state.page.total === 0 ? "0" : `${offset + 1}-${Math.min(offset + PAGE, state.page.total)}`} of {state.page.total}</span>
            <button type="button" disabled={offset + PAGE >= state.page.total} onClick={() => setOffset(offset + PAGE)}>Next</button>
          </div>
        </>
      )}
      <h3>Alert settings</h3>
      {!canEditSettings && <Notice kind="info">You can view the alert settings but not change them.</Notice>}
      {settings.status === "loading" && <Loading label="Loading settings…" />}
      {settings.status === "error" && <><Notice>{describeError(settings.error)}</Notice><button type="button" onClick={settings.reload}>Try again</button></>}
      {settings.status === "ready" && <ul className="list">{settings.data.items.map((s) => <SettingRow key={s.alert_type} s={s} canEdit={canEditSettings} onSaved={settings.reload} />)}</ul>}
    </section>
  );
}
