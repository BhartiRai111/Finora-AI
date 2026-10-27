"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import { formatMoney } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { humanize, isMoneyColumn, isPercentColumn, saveBlob, type Forecast, type Report } from "@/lib/insights";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const SERIES = new Set(["cash-flow", "business-performance"]);
const TITLES: Record<string, string> = {
  "cash-flow": "Income, expenses and net cash flow",
  "budget-vs-actual": "Budget versus actual",
  "net-worth": "Net worth at month end",
  "business-performance": "Revenue, expenses and profit",
  receivables: "Receivables aging",
  "receivables-by-client": "Receivables by client",
  "project-profitability": "Project profitability",
};

function cell(value: string | undefined, column: string, currency: string): string {
  if (value === undefined || value === "") return "—";
  if (isPercentColumn(column)) return `${value}%`;
  if (isMoneyColumn(column)) return formatMoney(value, currency);
  return value;
}

type Applied = { name: string; from: string; to: string; granularity: string; compare: boolean; month: string; asOf: string };

function query(a: Applied): string {
  const q = new URLSearchParams();
  if (SERIES.has(a.name)) {
    if (a.from) q.set("from", a.from);
    if (a.to) q.set("to", a.to);
    q.set("granularity", a.granularity);
    if (a.compare) q.set("compare", "previous");
  } else if (a.name === "project-profitability" || a.name === "net-worth") {
    if (a.from) q.set("from", a.from);
    if (a.to) q.set("to", a.to);
  } else if (a.name === "budget-vs-actual" && a.month) {
    q.set("month", a.month);
  } else if (a.name.startsWith("receivables") && a.asOf) {
    q.set("as_of", a.asOf);
  }
  return q.toString();
}

/** Reports and a baseline forecast for one workspace. Every number comes from the backend; the browser only lays it out. */
export function ReportsPanel({ domain, canExport }: { domain: "PERSONAL" | "BUSINESS"; canExport: boolean }) {
  const finance = useFinance();
  const names = domain === "BUSINESS" ? ["business-performance", "receivables", "receivables-by-client", "project-profitability"] : ["cash-flow", "budget-vs-actual", "net-worth"];
  const [draft, setDraft] = useState<Applied>({ name: names[0]!, from: "", to: "", granularity: "MONTH", compare: false, month: "", asOf: "" });
  const [applied, setApplied] = useState<Applied>(draft);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const report = useResource((signal) => {
    const qs = query(applied);
    return finance.get<Report>(`/finance-reports/${applied.name}${qs ? `?${qs}` : ""}`, signal);
  });

  function run() {
    if (draft.from && draft.to && draft.from > draft.to) return setError("The start date cannot be after the end date.");
    setError(null);
    setApplied(draft);
    report.reload();
  }

  async function exportCsv() {
    setBusy(true);
    setError(null);
    try {
      const qs = query(applied);
      const file = await finance.download(`/finance-reports/${applied.name}/export${qs ? `?${qs}` : ""}`);
      saveBlob(file.blob, file.filename);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  const set = (patch: Partial<Applied>) => setDraft((d) => ({ ...d, ...patch }));
  return (
    <>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="group" aria-label="Report options">
        <div className="column">
          <label htmlFor="rep-name">Report</label>
          <select id="rep-name" value={draft.name} onChange={(e) => set({ name: e.target.value })}>
            {names.map((n) => <option key={n} value={n}>{TITLES[n]}</option>)}
          </select>
        </div>
        {(SERIES.has(draft.name) || draft.name === "project-profitability" || draft.name === "net-worth") && (
          <>
            <div className="column"><label htmlFor="rep-from">From</label><input id="rep-from" type="date" value={draft.from} onChange={(e) => set({ from: e.target.value })} /></div>
            <div className="column"><label htmlFor="rep-to">To</label><input id="rep-to" type="date" value={draft.to} onChange={(e) => set({ to: e.target.value })} /></div>
          </>
        )}
        {SERIES.has(draft.name) && (
          <>
            <div className="column">
              <label htmlFor="rep-gran">Group by</label>
              <select id="rep-gran" value={draft.granularity} onChange={(e) => set({ granularity: e.target.value })}>
                <option value="MONTH">Month</option>
                <option value="WEEK">Week</option>
              </select>
            </div>
            <label><input type="checkbox" checked={draft.compare} onChange={(e) => set({ compare: e.target.checked })} /> Compare with the previous period</label>
          </>
        )}
        {draft.name === "budget-vs-actual" && (
          <div className="column"><label htmlFor="rep-month">Month</label><input id="rep-month" type="month" value={draft.month} onChange={(e) => set({ month: e.target.value })} /></div>
        )}
        {draft.name.startsWith("receivables") && (
          <div className="column"><label htmlFor="rep-asof">As of</label><input id="rep-asof" type="date" value={draft.asOf} onChange={(e) => set({ asOf: e.target.value })} /></div>
        )}
        <button type="button" onClick={run}>Show report</button>
        {canExport && <button type="button" onClick={() => void exportCsv()} disabled={busy}>{busy ? "Preparing…" : "Download CSV"}</button>}
      </div>

      {report.status === "loading" && <Loading label="Loading report…" />}
      {report.status === "error" && (
        <>
          <Notice>{describeError(report.error)}</Notice>
          <button type="button" onClick={report.reload}>Try again</button>
        </>
      )}
      {report.status === "ready" && <ReportView report={report.data} />}
      <Forecasts domain={domain} />
    </>
  );
}

function ReportView({ report: r }: { report: Report }) {
  return (
    <section aria-label={r.title}>
      <h2>{r.title}</h2>
      <p className="muted">{[r.from && r.to ? `${r.from} to ${r.to}` : r.to ? `as of ${r.to}` : null, r.granularity ? `by ${r.granularity.toLowerCase()}` : null].filter(Boolean).join(" · ")}. Each currency is shown on its own; nothing is converted.</p>
      {r.currencies.length === 0 && <p>No activity in this period yet, so there is nothing to report.</p>}
      {r.currencies.map((c) => (
        <div key={c.currency} className="column">
          <table>
            <caption>{c.currency}</caption>
            <thead>
              <tr><th scope="col">Period</th>{c.columns.map((col) => <th key={col} scope="col">{humanize(col)}</th>)}</tr>
            </thead>
            <tbody>
              {c.rows.map((row) => (
                <tr key={row.period} className={row.activity ? undefined : "muted"}>
                  <td>{row.period}{!row.activity && r.granularity ? " (no activity)" : ""}</td>
                  {c.columns.map((col) => <td key={col}>{cell(row.values[col], col, c.currency)}</td>)}
                </tr>
              ))}
              {Object.keys(c.totals).length > 0 && (
                <tr>
                  <th scope="row">Total</th>
                  {c.columns.map((col) => <td key={col}><strong>{c.totals[col] === undefined ? "" : cell(c.totals[col], col, c.currency)}</strong></td>)}
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ))}
      {r.comparison && (
        <>
          <h3>Compared with {r.comparison.from} to {r.comparison.to}</h3>
          {r.comparison.currencies.map((c) => (
            <table key={c.currency}>
              <caption>{c.currency}</caption>
              <thead><tr><th scope="col">Measure</th><th scope="col">This period</th><th scope="col">Earlier period</th><th scope="col">Change</th><th scope="col">Change %</th></tr></thead>
              <tbody>
                {Object.keys(c.current).map((col) => (
                  <tr key={col}>
                    <th scope="row">{humanize(col)}</th>
                    <td>{formatMoney(c.current[col]!, c.currency)}</td>
                    <td>{formatMoney(c.previous[col]!, c.currency)}</td>
                    <td>{formatMoney(c.change[col]!.amount, c.currency)}</td>
                    <td>{c.change[col]!.percent === null ? "n/a (earlier value is zero)" : `${c.change[col]!.percent}%`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        </>
      )}
      <details>
        <summary>How these figures are defined</summary>
        <ul>{r.definitions.map((d) => <li key={d}>{d}</li>)}</ul>
        <ul>{r.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      </details>
    </section>
  );
}

function Forecasts({ domain }: { domain: "PERSONAL" | "BUSINESS" }) {
  const finance = useFinance();
  const metrics = domain === "BUSINESS" ? ["net_cash", "received", "expenses", "invoiced_net"] : ["net", "income", "net_expense"];
  const [metric, setMetric] = useState(metrics[0]!);
  const [lookback, setLookback] = useState("6");
  const [horizon, setHorizon] = useState("3");
  const [applied, setApplied] = useState({ metric: metrics[0]!, lookback: "6", horizon: "3" });
  const forecast = useResource((signal) => finance.get<Forecast>(
    `/finance-forecast?metric=${applied.metric}&lookback_months=${applied.lookback}&horizon_months=${applied.horizon}`, signal));
  const [error, setError] = useState<string | null>(null);

  function run() {
    if (!/^\d{1,2}$/.test(lookback) || Number(lookback) < 3 || Number(lookback) > 12) return setError("History must be between 3 and 12 months.");
    if (!/^\d$/.test(horizon) || Number(horizon) < 1 || Number(horizon) > 6) return setError("Forecast length must be between 1 and 6 months.");
    setError(null);
    setApplied({ metric, lookback, horizon });
    forecast.reload();
  }

  return (
    <section aria-labelledby="forecast-title">
      <h2 id="forecast-title">Forecast (an estimate)</h2>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="group" aria-label="Forecast options">
        <div className="column">
          <label htmlFor="fc-metric">What to estimate</label>
          <select id="fc-metric" value={metric} onChange={(e) => setMetric(e.target.value)}>
            {metrics.map((m) => <option key={m} value={m}>{humanize(m)}</option>)}
          </select>
        </div>
        <div className="column"><label htmlFor="fc-lookback">Months of history</label><input id="fc-lookback" value={lookback} onChange={(e) => setLookback(e.target.value)} inputMode="numeric" /></div>
        <div className="column"><label htmlFor="fc-horizon">Months ahead</label><input id="fc-horizon" value={horizon} onChange={(e) => setHorizon(e.target.value)} inputMode="numeric" /></div>
        <button type="button" onClick={run}>Update forecast</button>
      </div>
      {forecast.status === "loading" && <Loading label="Loading forecast…" />}
      {forecast.status === "error" && (
        <>
          <Notice>{describeError(forecast.error)}</Notice>
          <button type="button" onClick={forecast.reload}>Try again</button>
        </>
      )}
      {forecast.status === "ready" && <ForecastView f={forecast.data} />}
    </section>
  );
}

function ForecastView({ f }: { f: Forecast }) {
  return (
    <>
      <Notice kind="info">{f.label}. History used: {f.history_from} to {f.history_to}.</Notice>
      {f.currencies.length === 0 && <p>There is no recorded history yet, so nothing can be estimated.</p>}
      {f.currencies.map((c) => (
        <div key={c.currency} className="card column" role="group" aria-label={`${c.currency} forecast`}>
          <h3>{c.currency}</h3>
          <p>Data coverage: {c.coverage.months_with_data} of {c.coverage.months_in_window} months had activity{c.coverage.missing_months.length > 0 && ` (missing: ${c.coverage.missing_months.join(", ")})`}.</p>
          {c.status === "INSUFFICIENT_HISTORY" && <Notice kind="info">{c.message}</Notice>}
          {c.status === "ESTIMATE" && c.estimate_per_month && (
            <>
              <p>
                Estimated per month: <strong>{formatMoney(c.estimate_per_month, c.currency)}</strong> (indicative range {formatMoney(c.low!, c.currency)} to {formatMoney(c.high!, c.currency)}; median {formatMoney(c.median!, c.currency)}).
                Confidence: <strong>{c.confidence === "LOW" ? "low" : "moderate"}</strong>.
              </p>
              <table>
                <caption>Projection</caption>
                <thead><tr><th scope="col">Month</th><th scope="col">Estimate</th><th scope="col">Range</th></tr></thead>
                <tbody>{c.projection.map((p) => <tr key={p.month}><td>{p.month}</td><td>{formatMoney(p.estimate, c.currency)}</td><td>{formatMoney(p.low, c.currency)} to {formatMoney(p.high, c.currency)}</td></tr>)}</tbody>
              </table>
              <table>
                <caption>History</caption>
                <thead><tr><th scope="col">Month</th><th scope="col">Actual</th><th scope="col">Note</th></tr></thead>
                <tbody>{c.history.map((h) => <tr key={h.month}><td>{h.month}</td><td>{h.activity ? formatMoney(h.value, c.currency) : "—"}</td><td>{!h.activity ? "no activity, left out" : h.outlier ? "unusual month" : ""}</td></tr>)}</tbody>
              </table>
            </>
          )}
          {c.warnings.length > 0 && <ul aria-label="Warnings">{c.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
        </div>
      ))}
      <details>
        <summary>Assumptions and limits</summary>
        <ul>{f.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
      </details>
      <p className="muted">AI explanation: {f.ai_explanation.message}</p>
    </>
  );
}
