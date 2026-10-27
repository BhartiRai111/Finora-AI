"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import type { CurrencySummary, Profitability } from "@/lib/business";
import { formatMoney } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

type Overview = { from: string; to: string; as_of: string; currencies: CurrencySummary[] };

/** Backend-computed summaries: nothing here is calculated in the browser. */
export function BusinessOverview({ refreshKey }: { refreshKey: number }) {
  const finance = useFinance();
  const [range, setRange] = useState({ from: "", to: "" });
  const data = useResource(async (signal) => {
    void refreshKey;
    const q = new URLSearchParams();
    if (range.from) q.set("from", range.from);
    if (range.to) q.set("to", range.to);
    const qs = q.toString() ? `?${q.toString()}` : "";
    const [overview, projects] = await Promise.all([
      finance.get<Overview>(`/business-summaries/overview${qs}`, signal),
      finance.get<{ items: Profitability[] }>(`/business-summaries/projects${qs}`, signal),
    ]);
    return { overview, projects: projects.items };
  });
  const [draft, setDraft] = useState({ from: "", to: "" });
  const [error, setError] = useState<string | null>(null);

  function apply() {
    if (draft.from && draft.to && draft.from > draft.to) return setError("The start date cannot be after the end date.");
    setError(null);
    setRange(draft);
    data.reload();
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="group" aria-label="Period">
        <div className="column"><label htmlFor="ov-from">From</label><input id="ov-from" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} /></div>
        <div className="column"><label htmlFor="ov-to">To</label><input id="ov-to" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} /></div>
        <button type="button" onClick={apply}>Show period</button>
      </div>
      {data.status === "loading" && <Loading label="Loading summary…" />}
      {data.status === "error" && (
        <>
          <Notice>{describeError(data.error)}</Notice>
          <button type="button" onClick={data.reload}>Try again</button>
        </>
      )}
      {data.status === "ready" && (
        <>
          <p className="muted">Period {data.data.overview.from} to {data.data.overview.to}. Receivables are as of {data.data.overview.as_of}. Each currency is reported on its own; nothing is converted.</p>
          {data.data.overview.currencies.length === 0 && <p>Nothing recorded in this period yet. Issue an invoice or record an expense to see your figures.</p>}
          {data.data.overview.currencies.map((c) => (
            <section key={c.currency} className="card column" aria-label={`${c.currency} summary`}>
              <h2>{c.currency}</h2>
              <p>Invoiced (excluding tax) <strong>{formatMoney(c.invoiced_net, c.currency)}</strong> across {c.invoice_count} invoice{c.invoice_count === 1 ? "" : "s"} · tax as entered {formatMoney(c.invoiced_tax, c.currency)}</p>
              <p>Received {formatMoney(c.received, c.currency)} · Expenses {formatMoney(c.expenses, c.currency)} · <strong>Net cash {formatMoney(c.net_cash, c.currency)}</strong></p>
              <p><strong>Operating result {formatMoney(c.operating_result, c.currency)}</strong> <span className="muted">(invoiced excluding tax − expenses)</span></p>
              <p>Receivables {formatMoney(c.receivables, c.currency)} · <strong>Overdue {formatMoney(c.overdue, c.currency)}</strong>{c.unallocated_payments !== "0.00" && c.unallocated_payments !== "0" && ` · Unallocated payments ${formatMoney(c.unallocated_payments, c.currency)}`}</p>
              {c.aging.length > 0 && (
                <table>
                  <caption>Receivables aging</caption>
                  <thead><tr>{c.aging.map((a) => <th key={a.label} scope="col">{a.label}</th>)}</tr></thead>
                  <tbody><tr>{c.aging.map((a) => <td key={a.label}>{formatMoney(a.amount, c.currency)}</td>)}</tr></tbody>
                </table>
              )}
              {c.expenses_by_category.length > 0 && (
                <ul aria-label={`${c.currency} expenses by category`}>
                  {c.expenses_by_category.map((x) => <li key={x.category}>{x.category}: {formatMoney(x.amount, c.currency)}</li>)}
                </ul>
              )}
            </section>
          ))}
          <h2>Project profitability</h2>
          {data.data.projects.length === 0 ? <p>No projects yet.</p> : (
            <table>
              <caption className="visually-hidden">Project profitability</caption>
              <thead><tr><th scope="col">Project</th><th scope="col">Revenue (excl. tax)</th><th scope="col">Costs</th><th scope="col">Profit</th><th scope="col">Margin</th><th scope="col">Budget</th></tr></thead>
              <tbody>
                {data.data.projects.map((p) => (
                  <tr key={p.project_id}>
                    <td>{p.project_name}</td>
                    <td>{formatMoney(p.revenue_net, p.currency)}</td>
                    <td>{formatMoney(p.costs, p.currency)}</td>
                    <td>{formatMoney(p.profit, p.currency)}</td>
                    <td>{p.margin_percent === null ? "—" : `${p.margin_percent}%`}</td>
                    <td>{p.budget ? (p.over_budget ? "Over budget" : `${p.budget_used_percent}% used`) : "No budget"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}
